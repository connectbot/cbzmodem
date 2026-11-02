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
import kotlin.test.fail

class SessionTest {
    @Test fun fragmentedBatchesIncludingEmptyFiles() = runBlocking {
        val a = ArrayDeque<ByteArray>()
        val b = ArrayDeque<ByteArray>()
        val received = linkedMapOf<String, ByteArrayOutputStream>()
        val complete = mutableListOf<String>()
        val receiver = Session(output = { a.add(it) }, receive = { metadata ->
            val out = ByteArrayOutputStream()
            received[metadata.name] = out
            object : Download {
                override suspend fun write(bytes: ByteArray) {
                    out.write(bytes)
                }
                override suspend fun complete() {
                    complete.add(metadata.name)
                }
                override suspend fun abort() {
                    fail("Unexpected abort")
                }
            }
        })
        val sender = Session(output = { b.add(it) }, receive = { fail("Unexpected receive") })
        val sources = listOf(byteArrayOf(), ByteArray(12345) { it.toByte() })
        val files = sources.mapIndexed { index, data ->
            object : Upload {
                override val name = "$index.dat"
                override val size = data.size.toLong()
                override suspend fun read(offset: Long, length: Int) = data.copyOfRange(offset.toInt(), offset.toInt() + length)
            }
        }
        receiver.start(Header(Framing.ZRQINIT))
        a.clear() // The detection layer already consumed the receiver's initial ZRINIT.
        sender.start(Header(Framing.ZRINIT), files)
        repeat(10000) {
            while (a.isNotEmpty()) a.removeFirst().forEach { sender.accept(byteArrayOf(it)) }
            while (b.isNotEmpty()) b.removeFirst().forEach { receiver.accept(byteArrayOf(it)) }
            if (sender.done && receiver.done) return@repeat
        }
        assertTrue(sender.done)
        assertTrue(receiver.done)
        assertEquals(listOf("0.dat", "1.dat"), complete)
        sources.forEachIndexed { index, data -> assertContentEquals(data, received["$index.dat"]!!.toByteArray()) }
    }

    @Test fun retryBudgetCancels() = runBlocking {
        val wire = mutableListOf<ByteArray>()
        val session = Session(output = { wire.add(it) }, receive = { null }, maxRetries = 2)
        session.start(Header(Framing.ZRQINIT))
        repeat(3) { session.timeout() }
        assertTrue(session.done)
        assertEquals("Transfer timed out", session.failure)
        assertTrue(wire.last().take(8).all { it == 0x18.toByte() })
    }
}
