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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FramingTest {
    @Test fun crcVector() {
        assertEquals(0x31c3, Framing.crc16("123456789".toByteArray()))
    }

    @Test fun headerRoundTrip() {
        for (position in listOf(0L, 1L, 0xffffffffL)) {
            val header = Header(Framing.ZRPOS, position)
            val bytes = Framing.header(header)
            for (size in 0 until bytes.size) assertNull(Framing.parseHeader(bytes.copyOf(size)))
            assertEquals(header, Framing.parseHeader(bytes)!!.header)
        }
    }

    @Test fun everyByteRoundTrip() {
        val bytes = ByteArray(256) { it.toByte() }
        for (end in Framing.ZCRCE..Framing.ZCRCW) {
            val wire = Framing.packet(bytes, end)
            for (size in 0 until wire.size) assertNull(Framing.parsePacket(wire.copyOf(size), false))
            val parsed = Framing.parsePacket(wire, false)!!
            assertContentEquals(bytes, parsed.bytes)
            assertEquals(end, parsed.end)
        }
    }

    @Test fun rejectsCorruption() {
        val wire = Framing.packet("hello".toByteArray(), Framing.ZCRCW)
        wire[0] = 'j'.code.toByte()
        assertFailsWith<IllegalArgumentException> { Framing.parsePacket(wire, false) }
    }

    @Test fun detectionAcrossEveryBoundary() {
        val wire = "prompt>".toByteArray() + Framing.header(Header(Framing.ZRQINIT)) + "tail".toByteArray()
        for (split in 0..wire.size) {
            val detector = Detector()
            val first = detector.accept(wire.copyOf(split))
            if (first.request != null) continue
            val second = detector.accept(wire.copyOfRange(split, wire.size))
            assertEquals(Framing.ZRQINIT, second.request?.type)
            assertContentEquals("prompt>".toByteArray(), first.terminal + second.terminal)
            assertContentEquals("tail".toByteArray(), second.remainder)
        }
    }

    @Test fun falseCandidatesAreLossless() {
        val detector = Detector()
        val bytes = "hello **not a header *\u0018Binvalid\nworld".toByteArray()
        val out = ByteArrayOutputStream()
        bytes.forEach { out.write(detector.accept(byteArrayOf(it)).terminal) }
        out.write(detector.finish())
        assertContentEquals(bytes, out.toByteArray())
    }
}
