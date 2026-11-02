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

/** Holds only a possible initial header, never arbitrary terminal output. One owner per stream. */
class Detector {
    private var candidate = byteArrayOf()
    data class Result(val terminal: ByteArray, val request: Header?, val remainder: ByteArray)

    fun accept(bytes: ByteArray): Result {
        val input = candidate + bytes
        candidate = byteArrayOf()
        val terminal = ByteArrayOutputStream()
        var offset = 0

        while (offset < input.size) {
            if (input[offset] != ZPAD.toByte()) {
                terminal.write(input[offset++].toInt())
                continue
            }

            val tail = input.copyOfRange(offset, input.size)
            val parsed = try {
                Framing.parseHeader(tail)
            } catch (_: IllegalArgumentException) {
                terminal.write(input[offset++].toInt())
                continue
            }

            if (parsed == null) {
                if (tail.size <= TransferLimits.MAX_DETECTION_BYTES) {
                    candidate = tail
                    break
                }
                terminal.write(input[offset++].toInt())
                continue
            }

            if (parsed.header.type == Framing.ZRQINIT || parsed.header.type == Framing.ZRINIT) {
                return Result(terminal.toByteArray(), parsed.header, tail.copyOfRange(parsed.consumed, tail.size))
            }

            terminal.write(input[offset++].toInt())
        }

        return Result(terminal.toByteArray(), null, byteArrayOf())
    }

    fun finish(): ByteArray = candidate.also { candidate = byteArrayOf() }
}
