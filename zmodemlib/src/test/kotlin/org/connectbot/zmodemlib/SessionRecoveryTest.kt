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

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRecoveryTest {
    @Test
    fun corruptSubpacketResumesAtLastCommittedOffset() = runBlocking {
        val output = mutableListOf<ByteArray>()
        val downloaded = ByteArrayOutputStream()
        var completed = false
        val receiver = Session(output = { output.add(it) }, receive = {
            object : Download {
                override suspend fun write(bytes: ByteArray) {
                    downloaded.write(bytes)
                }
                override suspend fun complete() {
                    completed = true
                }
                override suspend fun abort() {
                    error("Unexpected abort")
                }
            }
        })
        receiver.start(Header(Framing.ZRQINIT))
        receiver.accept(Framing.header(Header(Framing.ZFILE)) + Framing.packet("file.bin\u00006 0\u0000".toByteArray(), Framing.ZCRCW))
        receiver.accept(Framing.header(Header(Framing.ZDATA)) + Framing.packet("abc".toByteArray(), Framing.ZCRCQ))
        val corrupt = Framing.packet("bad".toByteArray(), Framing.ZCRCE)
        corrupt[0] = 'x'.code.toByte()
        receiver.accept(corrupt)
        assertEquals(Header(Framing.ZRPOS, 3), Framing.parseHeader(output.last())!!.header)
        assertContentEquals("abc".toByteArray(), downloaded.toByteArray())
        receiver.accept(Framing.header(Header(Framing.ZDATA, 3)) + Framing.packet("def".toByteArray(), Framing.ZCRCE))
        receiver.accept(Framing.header(Header(Framing.ZEOF, 6)))
        assertTrue(completed)
        assertContentEquals("abcdef".toByteArray(), downloaded.toByteArray())
        receiver.accept(Framing.header(Header(Framing.ZFIN)))
        assertContentEquals("prompt> ".toByteArray(), receiver.accept("OOprompt> ".toByteArray()))
        assertTrue(receiver.done)
    }

    @Test
    fun remoteCancellationAbortsIncompleteDownload() = runBlocking {
        var aborted = false
        var completed = false
        val receiver = Session(output = {}, receive = {
            object : Download {
                override suspend fun write(bytes: ByteArray) = Unit
                override suspend fun complete() {
                    completed = true
                }
                override suspend fun abort() {
                    aborted = true
                }
            }
        })
        receiver.start(Header(Framing.ZRQINIT))
        receiver.accept(Framing.header(Header(Framing.ZFILE)) + Framing.packet("file.bin\u00001 0\u0000".toByteArray(), Framing.ZCRCW))
        receiver.accept(byteArrayOf(24, 24))
        receiver.accept(byteArrayOf(24, 24, 24))
        assertTrue(receiver.done)
        assertTrue(aborted)
        assertFalse(completed)
    }

    @Test
    fun peerCancellationPreservesItsFollowingShellPrompt() = runBlocking {
        val receiver = Session(output = {}, receive = { null })
        receiver.start(Header(Framing.ZRQINIT))
        val prompt = "shell> ".toByteArray()
        assertContentEquals(prompt, receiver.accept(Framing.cancelSequence() + prompt))
        assertTrue(receiver.done)
    }

    @Test
    fun missingFinalConfirmationCompletesWithoutRetriesOrFailure() = runBlocking {
        val wire = mutableListOf<ByteArray>()
        val receiver = Session(output = { wire.add(it) }, receive = { null })
        receiver.start(Header(Framing.ZRQINIT))
        receiver.accept(Framing.header(Header(Framing.ZFIN)))
        assertTrue(receiver.awaitingFinishConfirmation)
        assertFalse(receiver.done)
        val writes = wire.size
        receiver.timeout()
        assertTrue(receiver.done)
        assertFalse(receiver.awaitingFinishConfirmation)
        assertNull(receiver.failure)
        assertEquals(writes, wire.size)
        assertContentEquals("shell>".toByteArray(), receiver.accept("shell>".toByteArray()))
    }

    @Test
    fun shellOutputWithoutFinalConfirmationIsPreserved() = runBlocking {
        for (prompt in listOf("shell> ", "Only a prompt> ", "\r\nshell> ")) {
            val receiver = Session(output = {}, receive = { null })
            receiver.start(Header(Framing.ZRQINIT))
            val tail = receiver.accept(Framing.header(Header(Framing.ZFIN)) + prompt.toByteArray())
            assertTrue(receiver.done)
            assertNull(receiver.failure)
            assertContentEquals(prompt.toByteArray(), tail)
        }
    }

    @Test
    fun finalConfirmationCanSpanReads() = runBlocking {
        val receiver = Session(output = {}, receive = { null })
        receiver.start(Header(Framing.ZRQINIT))
        receiver.accept(Framing.header(Header(Framing.ZFIN)) + "O".toByteArray())
        assertFalse(receiver.done)
        assertTrue(receiver.awaitingFinishConfirmation)
        assertContentEquals("shell>".toByteArray(), receiver.accept("Oshell>".toByteArray()))
        assertTrue(receiver.done)
    }

    @Test
    fun timeoutBeforeFinalHeaderStillRetries() = runBlocking {
        val wire = mutableListOf<ByteArray>()
        val receiver = Session(output = { wire.add(it) }, receive = { null })
        receiver.start(Header(Framing.ZRQINIT))
        val initial = wire.single()
        receiver.timeout()
        assertFalse(receiver.done)
        assertFalse(receiver.awaitingFinishConfirmation)
        assertContentEquals(initial, wire.last())
        assertEquals(2, wire.size)
    }

    @Test
    fun missingFileEofCannotBeTreatedAsSuccessfulCompletion() = runBlocking {
        var aborted = false
        val receiver = Session(output = {}, receive = {
            object : Download {
                override suspend fun write(bytes: ByteArray) = Unit
                override suspend fun complete() = error("Unexpected completion")
                override suspend fun abort() {
                    aborted = true
                }
            }
        })
        receiver.start(Header(Framing.ZRQINIT))
        receiver.accept(Framing.header(Header(Framing.ZFILE)) + Framing.packet("file.bin\u00000 0\u0000".toByteArray(), Framing.ZCRCW))
        assertFailsWith<IllegalArgumentException> { receiver.accept(Framing.header(Header(Framing.ZFIN))) }
        assertFalse(receiver.done)
        assertFalse(receiver.awaitingFinishConfirmation)
        receiver.cancel(notify = false)
        assertTrue(aborted)
    }

    @Test
    fun senderStillWaitsForReceiverFinishHeader() = runBlocking {
        val wire = mutableListOf<ByteArray>()
        val sender = Session(output = { wire.add(it) }, receive = { null })
        sender.start(Header(Framing.ZRINIT))
        sender.timeout()
        assertFalse(sender.done)
        assertFalse(sender.awaitingFinishConfirmation)
        assertEquals(2, wire.size)
        assertContentEquals(wire.first(), wire.last())
    }

    @Test
    fun emptyUploadBatchFinishesWithoutCreatingFiles() = runBlocking {
        val wire = mutableListOf<ByteArray>()
        val sender = Session(output = { wire.add(it) }, receive = { error("Unexpected receive") })
        sender.start(Header(Framing.ZRINIT))
        assertEquals(Header(Framing.ZFIN), Framing.parseHeader(wire.single())!!.header)
        val trailing = sender.accept(Framing.header(Header(Framing.ZFIN)) + "shell".toByteArray())
        assertTrue(sender.done)
        assertContentEquals("OO".toByteArray(), wire.last())
        assertContentEquals("shell".toByteArray(), trailing)
    }
}
