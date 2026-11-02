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
import kotlin.test.assertTrue

class SessionWindowTest {
    private val content = ByteArray(512 * 1024) { it.toByte() }
    private val file = object : Upload {
        override val name = "window.bin"
        override val size = content.size.toLong()
        override suspend fun read(offset: Long, length: Int) = content.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private fun verifyWindow(wire: ByteArray, position: Int, length: Int) {
        val header = Framing.parseHeader(wire)!!
        assertEquals(Header(Framing.ZDATA, position.toLong()), header.header)
        var offset = header.consumed
        val bytes = ByteArrayOutputStream()
        while (offset < wire.size) {
            val packet = Framing.parsePacket(wire.copyOfRange(offset, wire.size), false)!!
            offset += packet.consumed
            assertTrue(packet.bytes.size <= 1024)
            assertEquals(if (offset == wire.size) Framing.ZCRCW else Framing.ZCRCG, packet.end)
            bytes.write(packet.bytes)
        }
        assertContentEquals(content.copyOfRange(position, position + length), bytes.toByteArray())
    }

    @Test
    fun acknowledgedWindowsGrowAndStopAtConfiguredMaximum() = runBlocking {
        val wire = mutableListOf<ByteArray>()
        val session = Session(output = { wire.add(it) }, receive = { null })
        session.start(Header(Framing.ZRINIT, 0x03000000), listOf(file))
        wire.clear()
        session.accept(Framing.header(Header(Framing.ZRPOS)))
        verifyWindow(wire.single(), 0, 16 * 1024)
        session.accept(Framing.header(Header(Framing.ZACK, 16 * 1024)))
        verifyWindow(wire.last(), 16 * 1024, 32 * 1024)
        session.accept(Framing.header(Header(Framing.ZACK, 48 * 1024)))
        verifyWindow(wire.last(), 48 * 1024, 64 * 1024)
        session.accept(Framing.header(Header(Framing.ZACK, 112 * 1024)))
        verifyWindow(wire.last(), 112 * 1024, 64 * 1024)
        assertEquals(4, wire.size) // One ACK gates each whole window, not every subpacket.
    }

    @Test
    fun receiverBufferLimitAndNonOverlappingPeersAreRespected() = runBlocking {
        for ((capabilities, limit) in listOf(0x03001000L to 4096, 0L to 1024, 0x00000080L to 128)) {
            val wire = mutableListOf<ByteArray>()
            val session = Session(output = { wire.add(it) }, receive = { null })
            session.start(Header(Framing.ZRINIT, capabilities), listOf(file))
            wire.clear()
            session.accept(Framing.header(Header(Framing.ZRPOS)))
            verifyWindow(wire.last(), 0, limit)
            session.accept(Framing.header(Header(Framing.ZACK, limit.toLong())))
            verifyWindow(wire.last(), limit, limit)
        }
    }

    @Test
    fun recoveryShrinksTheWindowAndIgnoresStaleAcknowledgments() = runBlocking {
        val wire = mutableListOf<ByteArray>()
        val session = Session(output = { wire.add(it) }, receive = { null })
        session.start(Header(Framing.ZRINIT, 0x03000000), listOf(file))
        wire.clear()
        session.accept(Framing.header(Header(Framing.ZRPOS)))
        session.accept(Framing.header(Header(Framing.ZRPOS, 4096)))
        verifyWindow(wire.last(), 4096, 8192)
        session.accept(Framing.header(Header(Framing.ZACK, 16384)))
        assertEquals(2, wire.size)
        session.accept(Framing.header(Header(Framing.ZACK, 12288)))
        verifyWindow(wire.last(), 12288, 16384)
    }

    @Test
    fun localAbortUsesStandardSequenceAndLeavesTheTransportToItsOwner() = runBlocking {
        val wire = mutableListOf<ByteArray>()
        val session = Session(output = { wire.add(it) }, receive = { null })
        session.start(Header(Framing.ZRINIT, 0x03000000), listOf(file))
        session.cancel()
        assertContentEquals(ByteArray(8) { 24 } + ByteArray(10) { 8 }, wire.last())
        assertTrue(session.done)
        assertContentEquals("shell prompt".toByteArray(), session.accept("shell prompt".toByteArray()))
    }
}
