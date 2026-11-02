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

import kotlinx.coroutines.runBlocking
import org.connectbot.zmodemlib.Detector
import org.connectbot.zmodemlib.Download
import org.connectbot.zmodemlib.Session
import org.connectbot.zmodemlib.Upload
import java.io.File
import java.io.RandomAccessFile

/** CLI protocol harness: send FILE... or receive DIRECTORY; wire data exclusively on stdio. */
fun main(args: Array<String>) = runBlocking {
    require(args.isNotEmpty())
    val files = if (args[0] == "send") {
        args.drop(1).map { path ->
            val file = File(path)
            object : Upload {
                override val name = file.name
                override val size = file.length()
                override suspend fun read(offset: Long, length: Int): ByteArray = RandomAccessFile(file, "r").use {
                    it.seek(offset)
                    ByteArray(length).also { bytes -> it.readFully(bytes) }
                }
            }
        }
    } else {
        emptyList()
    }
    val window = System.getenv("ZMODEM_WINDOW_BYTES")?.toIntOrNull() ?: 64 * 1024
    val session = Session(maxWindowBytes = window, initialWindowBytes = minOf(16 * 1024, window), output = {
        System.out.write(it)
        System.out.flush()
    }, receive = { info ->
        val directory = File(args[1]).canonicalFile
        val file = File(directory, info.name).canonicalFile
        require(file.parentFile == directory && !file.exists())
        val out = file.outputStream()
        object : Download {
            override suspend fun write(bytes: ByteArray) {
                out.write(bytes)
            }
            override suspend fun complete() {
                out.close()
            }
            override suspend fun abort() {
                out.close()
                file.delete()
            }
        }
    })
    val detector = Detector()
    var started = false
    val buffer = ByteArray(4096)
    while (!session.done) {
        val count = System.`in`.read(buffer)
        if (count < 0) {
            session.cancel("EOF", notify = false)
            break
        }
        val bytes = buffer.copyOf(count)
        if (started) {
            session.accept(bytes)
        } else {
            val detection = detector.accept(bytes)
            if (detection.request != null) {
                started = true
                session.start(detection.request!!, files)
                session.accept(detection.remainder)
            }
        }
    }
    check(session.failure == null) { session.failure ?: "failure" }
}
