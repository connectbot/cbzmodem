/*
 * ConnectBot Z-Modem Library
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.zmodemlib

import ru.nsk.kstatemachine.event.Event
import ru.nsk.kstatemachine.state.initialState
import ru.nsk.kstatemachine.state.onEntry
import ru.nsk.kstatemachine.state.state
import ru.nsk.kstatemachine.state.transition
import ru.nsk.kstatemachine.statemachine.ProcessingResult
import ru.nsk.kstatemachine.statemachine.createStdLibStateMachine
import java.io.ByteArrayOutputStream

/** Metadata and a seekable source supplied by the consumer. No filesystem policy is imposed. */
interface Upload {
    val name: String
    val size: Long
    suspend fun read(offset: Long, length: Int): ByteArray
}

/** Each write is committed before acknowledgment. The consumer owns publication and cleanup. */
interface Download {
    suspend fun write(bytes: ByteArray)
    suspend fun complete()
    suspend fun abort()
}

data class Metadata(val name: String, val size: Long?)
data class Progress(val name: String, val bytes: Long, val total: Long?)

/**
 * Single-owner incremental session. Invoke [accept], [timeout], and [cancel] serially.
 * Callbacks may suspend and must execute off the UI thread. Output must finish its write before
 * returning. The consumer supplies response timers, selection deadlines, and stream ownership.
 */
class Session(
    private val output: suspend (ByteArray) -> Unit,
    private val receive: suspend (Metadata) -> Download?,
    private val progress: (Progress) -> Unit = {},
    private val maxRetries: Int = TransferLimits.DEFAULT_RETRIES,
    private val initialWindowBytes: Int = TransferLimits.INITIAL_WINDOW_BYTES,
    private val maxWindowBytes: Int = TransferLimits.DEFAULT_MAX_WINDOW_BYTES,
) {
    init {
        require(maxRetries >= 0)
        require(maxWindowBytes in 1..TransferLimits.MAX_WINDOW_BYTES)
        require(initialWindowBytes in 1..maxWindowBytes)
    }

    private enum class Phase { START, RECEIVE_HEADER, RECEIVE_METADATA, RECEIVE_DATA, SEND_INIT, SEND_POSITION, SEND_ACK, SEND_EOF, FINISH, DONE }
    private class Move(val phase: Phase) : Event
    private var phase = Phase.START
    private val lifecycle = createStdLibStateMachine {
        val nodes = Phase.entries.associateWith { if (it == Phase.START) initialState(it.name) else state(it.name) }
        // Each allowed lifecycle edge is declared here; packet fields stay outside state configuration.
        val edges = mapOf(
            Phase.START to setOf(Phase.RECEIVE_HEADER, Phase.SEND_POSITION, Phase.FINISH),
            Phase.RECEIVE_HEADER to setOf(Phase.RECEIVE_METADATA, Phase.RECEIVE_DATA, Phase.FINISH),
            Phase.RECEIVE_METADATA to setOf(Phase.RECEIVE_HEADER),
            Phase.RECEIVE_DATA to setOf(Phase.RECEIVE_HEADER),
            Phase.SEND_POSITION to setOf(Phase.SEND_ACK, Phase.SEND_EOF, Phase.FINISH),
            Phase.SEND_ACK to setOf(Phase.SEND_ACK, Phase.SEND_EOF, Phase.SEND_POSITION, Phase.FINISH),
            Phase.SEND_EOF to setOf(Phase.SEND_POSITION, Phase.SEND_ACK, Phase.SEND_EOF, Phase.FINISH),
            Phase.FINISH to setOf(Phase.FINISH, Phase.DONE),
        )

        nodes.forEach { (from, node) ->
            node.onEntry { phase = from }
            ((edges[from] ?: emptySet()) + Phase.DONE).forEach { to ->
                node.transition<Move> {
                    guard = { event.phase == to }
                    targetState = nodes.getValue(to)
                }
            }
        }
    }

    private suspend fun move(to: Phase) {
        if (phase == to) return
        check(lifecycle.processEvent(Move(to)) == ProcessingResult.PROCESSED) { "Invalid transition $phase -> $to" }
    }

    private var buffer = byteArrayOf()
    private var crc32 = false
    private var sink: Download? = null
    private var metadata: Metadata? = null
    private var offset = 0L
    private var uploads: Iterator<Upload> = emptyList<Upload>().iterator()
    private var upload: Upload? = null
    private var lastOutput = byteArrayOf()
    private var retries = 0
    private var cancellationRun = 0
    private var packetMode = false
    private var metadataPacket = false
    private var attentionPacket = false
    private var sending = false
    private var peerWindowLimit = TransferLimits.FALLBACK_WINDOW_BYTES
    private var windowBytes = TransferLimits.FALLBACK_WINDOW_BYTES

    private fun configureReceiver(header: Header) {
        val advertisedBuffer = (header.position and RECEIVER_BUFFER_MASK).toInt()
        val canOverlap = (header.position ushr ZF0_SHIFT).toInt() and CANOVIO != 0
        peerWindowLimit = when {
            advertisedBuffer != 0 -> minOf(advertisedBuffer, maxWindowBytes)
            canOverlap -> maxWindowBytes
            else -> minOf(TransferLimits.FALLBACK_WINDOW_BYTES, maxWindowBytes)
        }
        windowBytes = minOf(initialWindowBytes, peerWindowLimit)
    }

    private fun reduceWindow() {
        windowBytes = maxOf(minOf(TransferLimits.FALLBACK_WINDOW_BYTES, peerWindowLimit), windowBytes / TransferLimits.WINDOW_SCALE_FACTOR)
    }

    val done: Boolean get() = phase == Phase.DONE

    var failure: String? = null
        private set

    private suspend fun send(bytes: ByteArray) {
        lastOutput = bytes
        output(bytes)
    }

    private suspend fun header(type: Int, position: Long = 0) = send(Framing.header(Header(type, position)))

    suspend fun start(request: Header, files: List<Upload> = emptyList()) {
        check(phase == Phase.START)
        when (request.type) {
            Framing.ZRQINIT -> {
                move(Phase.RECEIVE_HEADER)
                // CANFDX | CANOVIO | CANFC32 | ESCCTL, ZF0 is the highest position byte.
                header(Framing.ZRINIT, RECEIVER_CAPABILITIES)
            }

            Framing.ZRINIT -> {
                require(files.all { it.size in 0..MAX_POSITION && it.name.isNotEmpty() && '\u0000' !in it.name })
                configureReceiver(request)
                sending = true
                uploads = files.iterator()
                nextUpload()
            }

            else -> error("Not a Z-Modem initiation")
        }
    }

    private suspend fun nextUpload() {
        if (!uploads.hasNext()) {
            move(Phase.FINISH)
            header(Framing.ZFIN)
            return
        }
        upload = uploads.next()
        offset = 0
        move(Phase.SEND_POSITION)
        val file = checkNotNull(upload)
        // ZCBIN in ZF0; never request text conversion or remote append/overwrite policy.
        send(Framing.header(Header(Framing.ZFILE, BINARY_FILE_FLAGS)) + Framing.packet((file.name + "\u0000" + file.size + " 0 0 0 0 0\u0000").toByteArray(Charsets.UTF_8), Framing.ZCRCW))
    }

    private suspend fun sendBlock() {
        val file = checkNotNull(upload)
        if (offset == file.size) {
            move(Phase.SEND_EOF)
            header(Framing.ZEOF, offset)
            return
        }

        val end = offset + minOf(windowBytes.toLong(), file.size - offset)
        val wire = ByteArrayOutputStream()

        wire.write(Framing.header(Header(Framing.ZDATA, offset)))

        while (offset < end) {
            val requested = minOf(TransferLimits.SUBPACKET_BYTES.toLong(), end - offset).toInt()
            val bytes = file.read(offset, requested)
            require(bytes.isNotEmpty() && bytes.size <= requested) { "Invalid upload read" }
            offset += bytes.size
            // Continue streaming within the window; only its final subpacket waits for an ACK.
            wire.write(Framing.packet(bytes, if (offset == end) Framing.ZCRCW else Framing.ZCRCG))
        }

        move(Phase.SEND_ACK)
        send(wire.toByteArray())
    }

    /** Returns terminal bytes following a completed transfer. Transfer input is never returned. */
    suspend fun accept(bytes: ByteArray): ByteArray {
        if (done) return bytes

        require(bytes.size <= TransferLimits.MAX_INPUT_BYTES) { "Split input into bounded chunks" }
        require(buffer.size + bytes.size <= TransferLimits.MAX_BUFFER_BYTES) { "Z-Modem input limit exceeded" }

        // Cancellation is a wire-level sequence and can span reads even while header
        // resynchronization discards unrelated bytes. Scan only newly received bytes.
        for ((index, byte) in bytes.withIndex()) {
            cancellationRun = if (byte == ZDLE.toByte()) cancellationRun + 1 else 0

            if (cancellationRun >= CANCEL_DETECT_COUNT) {
                cancel("Remote cancelled", notify = false)
                // The remote shell may already have printed its next prompt in this read.
                var tail = index + 1
                while (tail < bytes.size && bytes[tail] == ZDLE.toByte()) tail++
                while (tail < bytes.size && bytes[tail] == BACKSPACE.toByte()) tail++
                return bytes.copyOfRange(tail, bytes.size)
            }
        }

        buffer += bytes

        while (buffer.isNotEmpty() && !done) {
            if (phase == Phase.FINISH && !sending) {
                if (buffer.size < FINISH_CONFIRMATION.length) break
                if (FINISH_CONFIRMATION.indices.all { buffer[it] == FINISH_CONFIRMATION[it].code.toByte() }) {
                    buffer = buffer.copyOfRange(FINISH_CONFIRMATION.length, buffer.size)
                    move(Phase.DONE)
                    break
                }
            }

            if (packetMode) {
                val packet = try {
                    Framing.parsePacket(buffer, crc32)
                } catch (_: IllegalArgumentException) {
                    buffer = byteArrayOf()
                    packetMode = false
                    if (++retries > maxRetries) {
                        cancel("Too many CRC errors")
                        break
                    }
                    header(if (metadataPacket || attentionPacket) Framing.ZNAK else Framing.ZRPOS, offset)
                    break
                } ?: break
                buffer = buffer.copyOfRange(packet.consumed, buffer.size)
                retries = 0
                if (attentionPacket) {
                    attentionPacket = false
                    packetMode = false
                    header(Framing.ZACK, ATTENTION_ACK_VALUE)
                } else if (metadataPacket) {
                    metadataPacket = false
                    packetMode = false
                    val fields = packet.bytes.toString(Charsets.UTF_8).split('\u0000')
                    val name = fields.first()
                    val size = fields.getOrNull(1)?.substringBefore(' ')?.toLongOrNull()
                    require(size == null || size in 0..MAX_POSITION) { "File exceeds classic Z-Modem limit" }
                    metadata = Metadata(name, size)
                    sink = receive(checkNotNull(metadata))
                    offset = 0
                    move(Phase.RECEIVE_HEADER)
                    header(if (sink == null) Framing.ZSKIP else Framing.ZRPOS)
                } else {
                    checkNotNull(sink).write(packet.bytes)
                    offset += packet.bytes.size
                    require(offset <= MAX_POSITION && (metadata?.size == null || offset <= metadata!!.size!!))
                    progress(Progress(metadata!!.name, offset, metadata!!.size))
                    if (packet.end == Framing.ZCRCQ || packet.end == Framing.ZCRCW) header(Framing.ZACK, offset)
                    if (packet.end == Framing.ZCRCE || packet.end == Framing.ZCRCW) {
                        packetMode = false
                        move(Phase.RECEIVE_HEADER)
                    }
                }
                continue
            }

            // Protocol permits XON and padding outside binary frames.
            val start = buffer.indexOf(ZPAD.toByte())
            if (start < 0) {
                buffer = byteArrayOf()
                break
            }

            if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)

            val parsed = try {
                Framing.parseHeader(buffer)
            } catch (_: IllegalArgumentException) {
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            } ?: break

            buffer = buffer.copyOfRange(parsed.consumed, buffer.size)
            crc32 = parsed.crc32
            retries = 0

            val h = parsed.header

            when (h.type) {
                Framing.ZRQINIT -> if (!sending) header(Framing.ZRINIT, RECEIVER_CAPABILITIES)

                Framing.ZFILE -> if (!sending) {
                    metadataPacket = true
                    packetMode = true
                    move(Phase.RECEIVE_METADATA)
                }

                Framing.ZDATA -> if (sink != null) {
                    if (h.position != offset) {
                        header(Framing.ZRPOS, offset)
                    } else {
                        packetMode = true
                        move(Phase.RECEIVE_DATA)
                    }
                }

                Framing.ZEOF -> if (sink != null) {
                    if (h.position != offset || (metadata!!.size != null && offset != metadata!!.size)) {
                        header(Framing.ZRPOS, offset)
                    } else {
                        sink!!.complete()
                        sink = null
                        move(Phase.RECEIVE_HEADER)
                        header(Framing.ZRINIT, RECEIVER_CAPABILITIES)
                    }
                }

                Framing.ZRINIT -> if (sending && phase == Phase.SEND_EOF) {
                    configureReceiver(h)
                    nextUpload()
                }

                Framing.ZRPOS -> if (sending) {
                    require(h.position <= upload!!.size)
                    if (h.position < offset) reduceWindow()
                    offset = h.position
                    sendBlock()
                }

                Framing.ZACK -> if (sending && phase == Phase.SEND_ACK && h.position == offset) {
                    progress(Progress(upload!!.name, offset, upload!!.size))
                    windowBytes = minOf(windowBytes * TransferLimits.WINDOW_SCALE_FACTOR, peerWindowLimit)
                    sendBlock()
                }

                Framing.ZSKIP -> if (sending) nextUpload()

                Framing.ZNAK -> {
                    reduceWindow()
                    output(lastOutput)
                }

                Framing.ZFIN -> if (phase == Phase.FINISH && sending) {
                    output(FINISH_CONFIRMATION.toByteArray(Charsets.US_ASCII))
                    move(Phase.DONE)
                } else if (!sending) {
                    move(Phase.FINISH)
                    header(Framing.ZFIN)
                }

                Framing.ZSINIT -> { // Attention string follows; consume it and acknowledge.
                    attentionPacket = true
                    packetMode = true
                }
            }
        }

        if (done) return buffer.also { buffer = byteArrayOf() }

        return byteArrayOf()
    }

    suspend fun timeout() {
        if (done) return

        reduceWindow()

        if (++retries > maxRetries) cancel("Transfer timed out") else output(lastOutput)
    }

    suspend fun cancel(reason: String = "Cancelled", notify: Boolean = true) {
        if (done) return
        failure = reason
        move(Phase.DONE)

        try {
            if (notify) output(Framing.cancelSequence())
        } finally {
            sink?.abort()
            sink = null
            buffer = byteArrayOf()
        }
    }
}
