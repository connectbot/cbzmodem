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

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Decoded classic Z-Modem header. Position is unsigned and little endian on the wire. */
data class Header(
    val type: Int,
    val position: Long = 0,
)

/** Bounded incremental wire codec. The caller retains bytes until a complete frame is available. */
object Framing {
    /** Request receiver initialization. */
    const val ZRQINIT = 0

    /** Receiver capabilities and buffer size. */
    const val ZRINIT = 1

    /** Sender initialization; attention string follows. */
    const val ZSINIT = 2

    /** Acknowledge a position or initialization. */
    const val ZACK = 3

    /** File metadata follows. */
    const val ZFILE = 4

    /** Skip the current file. */
    const val ZSKIP = 5

    /** Retransmit the previous frame. */
    const val ZNAK = 6

    /** Finish the session. */
    const val ZFIN = 8

    /** Resume or retransmit from this position. */
    const val ZRPOS = 9

    /** File data follows. */
    const val ZDATA = 10

    /** End of file at this position. */
    const val ZEOF = 11

    /** End this data frame without acknowledgment. */
    const val ZCRCE = 0x68

    /** Continue streaming without acknowledgment. */
    const val ZCRCG = 0x69

    /** Continue streaming and request acknowledgment. */
    const val ZCRCQ = 0x6a

    /** End this data frame and wait for acknowledgment. */
    const val ZCRCW = 0x6b

    /** Standard abort sequence: eight CAN bytes followed by ten backspaces. */
    fun cancelSequence(): ByteArray = ByteArray(CANCEL_SEND_COUNT) { ZDLE.toByte() } + ByteArray(CANCEL_BACKSPACE_COUNT) { BACKSPACE.toByte() }

    data class ParsedHeader(
        val header: Header,
        val consumed: Int,
        val crc32: Boolean,
    )

    data class Packet(
        val bytes: ByteArray,
        val end: Int,
        val consumed: Int,
    )

    fun crc16(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            crc = crc xor ((b.toInt() and BYTE_MASK) shl Byte.SIZE_BITS)
            repeat(Byte.SIZE_BITS) { crc = if (crc and CRC16_HIGH_BIT != 0) (crc shl 1) xor CRC16_POLYNOMIAL else crc shl 1 }
            crc = crc and CRC16_MASK
        }
        return crc
    }

    fun header(value: Header): ByteArray {
        require(value.position in 0..MAX_POSITION)
        val body = byteArrayOf(value.type.toByte(), *ByteArray(POSITION_BYTES) { (value.position ushr (it * Byte.SIZE_BITS)).toByte() })
        val crc = crc16(body)
        return (
            "${ZPAD.toChar()}${ZPAD.toChar()}${ZDLE.toChar()}${ZHEX.toChar()}" +
                (body + byteArrayOf((crc ushr Byte.SIZE_BITS).toByte(), crc.toByte())).joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) } +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
    }

    private fun decode(
        input: ByteArray,
        start: Int,
        count: Int,
    ): Pair<ByteArray, Int>? {
        val out = ByteArrayOutputStream()
        var i = start

        while (out.size() < count) {
            if (i >= input.size) return null

            var b = input[i++].toInt() and BYTE_MASK

            if (isFlowControl(b)) continue

            if (b == ZDLE) {
                if (i == input.size) return null
                b = input[i++].toInt() and BYTE_MASK
                b =
                    when (b) {
                        ZRUB0 -> DELETE
                        ZRUB1 -> BYTE_MASK
                        else -> b xor ESCAPE_XOR
                    }
            }

            out.write(b)
        }
        return out.toByteArray() to i
    }

    fun parseHeader(input: ByteArray): ParsedHeader? {
        var i = 0

        while (i < input.size && input[i] == ZPAD.toByte()) i++

        if (i == input.size) return null

        require(i > 0 && input[i++].toInt() == ZDLE) { "Invalid Z-Modem prefix" }

        if (i == input.size) return null

        val format = input[i++].toInt()
        val crc32 = format == ZBIN32
        val body: ByteArray

        if (format == ZHEX) {
            if (input.size < i + HEX_HEADER_DIGITS) return null

            body =
                ByteArray(HEX_HEADER_BYTES) { n ->
                    val hi = Character.digit(input[i + n * HEX_DIGITS_PER_BYTE].toInt().toChar(), HEX_RADIX)
                    val lo = Character.digit(input[i + n * HEX_DIGITS_PER_BYTE + 1].toInt().toChar(), HEX_RADIX)
                    require(hi >= 0 && lo >= 0) { "Invalid hex header" }
                    ((hi shl NIBBLE_BITS) or lo).toByte()
                }

            i += HEX_HEADER_DIGITS

            // Consume the mandatory CR/LF, including parity-set LF.
            if (input.size < i + HEADER_TERMINATOR_BYTES) return null

            require(input[i].toInt() and ASCII_MASK == CARRIAGE_RETURN && input[i + 1].toInt() and ASCII_MASK == LINE_FEED)

            i += HEADER_TERMINATOR_BYTES
        } else {
            require(format == ZBIN || crc32) { "Unsupported header format" }

            val result = decode(input, i, HEADER_BODY_BYTES + if (crc32) CRC32_BYTES else CRC16_BYTES) ?: return null

            body = result.first

            i = result.second
        }

        verify(body, HEADER_BODY_BYTES, crc32)

        var position = 0L

        repeat(POSITION_BYTES) {
            position = position or ((body[it + 1].toLong() and BYTE_MASK.toLong()) shl (it * Byte.SIZE_BITS))
        }

        return ParsedHeader(Header(body[0].toInt() and BYTE_MASK, position), i, crc32)
    }

    private fun verify(
        body: ByteArray,
        size: Int,
        crc32: Boolean,
    ) {
        if (crc32) {
            val crc = CRC32().apply { update(body, 0, size) }.value
            var actual = 0L

            repeat(CRC32_BYTES) { actual = actual or ((body[size + it].toLong() and BYTE_MASK.toLong()) shl (it * Byte.SIZE_BITS)) }

            require(crc == actual) { "CRC-32 mismatch" }
        } else {
            require(
                crc16(body.copyOfRange(0, size)) == ((body[size].toInt() and BYTE_MASK) shl Byte.SIZE_BITS or (body[size + 1].toInt() and BYTE_MASK)),
            ) { "CRC-16 mismatch" }
        }
    }

    fun packet(
        bytes: ByteArray,
        end: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()

        fun quote(b: Int) {
            if (b < ASCII_CONTROL_LIMIT || b in PARITY_BIT..PARITY_CONTROL_LAST || b == DELETE ||
                b == BYTE_MASK
            ) {
                out.write(ZDLE)
                out.write(
                    when (b) {
                        DELETE -> ZRUB0
                        BYTE_MASK -> ZRUB1
                        else -> b xor ESCAPE_XOR
                    },
                )
            } else {
                out.write(b)
            }
        }

        bytes.forEach { quote(it.toInt() and BYTE_MASK) }
        out.write(ZDLE)
        out.write(end)

        val crc = crc16(bytes + end.toByte())

        quote(crc ushr Byte.SIZE_BITS)
        quote(crc and BYTE_MASK)

        return out.toByteArray()
    }

    fun parsePacket(
        input: ByteArray,
        crc32: Boolean,
        maxBytes: Int = TransferLimits.MAX_PACKET_BYTES,
    ): Packet? {
        val out = ByteArrayOutputStream()
        var i = 0

        while (i < input.size) {
            var b = input[i++].toInt() and BYTE_MASK

            if (isFlowControl(b)) continue

            if (b == ZDLE) {
                if (i == input.size) return null

                b = input[i++].toInt() and BYTE_MASK

                if (b in ZCRCE..ZCRCW) {
                    val crc = decode(input, i, if (crc32) CRC32_BYTES else CRC16_BYTES) ?: return null
                    val bytes = out.toByteArray()
                    verify(bytes + b.toByte() + crc.first, bytes.size + 1, crc32)
                    return Packet(bytes, b, crc.second)
                }

                b =
                    when (b) {
                        ZRUB0 -> DELETE
                        ZRUB1 -> BYTE_MASK
                        else -> b xor ESCAPE_XOR
                    }
            }

            require(out.size() < maxBytes) { "Z-Modem packet exceeds limit" }
            out.write(b)
        }

        return null
    }
}
