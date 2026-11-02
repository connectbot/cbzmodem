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

// Classic Z-Modem wire values. Flag names follow the protocol specification.
internal const val ZPAD = '*'.code
internal const val ZDLE = 0x18 // ASCII CAN (Ctrl-X), also used to introduce escaped bytes.
internal const val ZBIN = 'A'.code
internal const val ZHEX = 'B'.code
internal const val ZBIN32 = 'C'.code
internal const val ZRUB0 = 0x6c // Escaped DEL.
internal const val ZRUB1 = 0x6d // Escaped all-ones byte.
internal const val ESCAPE_XOR = 0x40
internal const val XON = 0x11
internal const val XOFF = 0x13
internal const val PARITY_BIT = 0x80
internal const val ASCII_MASK = 0x7f
internal const val DELETE = 0x7f
internal const val BYTE_MASK = 0xff
internal const val BACKSPACE = 0x08
internal const val CARRIAGE_RETURN = 0x0d
internal const val LINE_FEED = 0x0a
internal const val ASCII_CONTROL_LIMIT = 0x20
internal const val PARITY_CONTROL_LAST = 0x9f

internal const val CRC16_POLYNOMIAL = 0x1021 // CRC-16/XMODEM, initial remainder zero.
internal const val CRC16_HIGH_BIT = 0x8000
internal const val CRC16_MASK = 0xffff
internal const val CRC16_BYTES = 2
internal const val CRC32_BYTES = 4
internal const val POSITION_BYTES = 4
internal const val MAX_POSITION = 0xffffffffL // Classic Z-Modem uses unsigned 32-bit positions.
internal const val HEADER_BODY_BYTES = 1 + POSITION_BYTES // Frame type followed by position/flags.
internal const val HEX_HEADER_BYTES = HEADER_BODY_BYTES + CRC16_BYTES
internal const val HEX_DIGITS_PER_BYTE = 2
internal const val HEX_HEADER_DIGITS = HEX_HEADER_BYTES * HEX_DIGITS_PER_BYTE
internal const val HEX_RADIX = 16
internal const val NIBBLE_BITS = 4
internal const val HEADER_TERMINATOR_BYTES = 2 // CR followed by LF (possibly parity-set).
internal const val ZF0_SHIFT = 3 * Byte.SIZE_BITS // ZF0 occupies the high byte of the position word.
internal const val RECEIVER_BUFFER_MASK = 0xffffL // ZP0 and ZP1 contain the receiver buffer size.

internal const val CANFDX = 0x01 // Full duplex.
internal const val CANOVIO = 0x02 // Overlapped I/O; zero buffer size permits continuous streaming.
internal const val CANFC32 = 0x20 // Can receive CRC-32 frames.
internal const val ESCCTL = 0x40 // Request escaping of control characters.
internal const val ZCBIN = 0x01 // Binary file conversion mode.
internal const val RECEIVER_FLAGS = CANFDX or CANOVIO or CANFC32 or ESCCTL
internal const val RECEIVER_CAPABILITIES = RECEIVER_FLAGS.toLong() shl ZF0_SHIFT
internal const val BINARY_FILE_FLAGS = ZCBIN.toLong() shl ZF0_SHIFT
internal const val CANCEL_SEND_COUNT = 8
internal const val CANCEL_DETECT_COUNT = 5
internal const val CANCEL_BACKSPACE_COUNT = 10
internal const val FINISH_CONFIRMATION = "OO"
internal const val ATTENTION_ACK_VALUE = 1L // ZACK value sent after consuming the ZSINIT attention string.

/** Implementation policies, separate from the protocol's wire values and field widths. */
internal object TransferLimits {
    const val DEFAULT_RETRIES = 10
    const val INITIAL_WINDOW_BYTES = 16 * 1024
    const val DEFAULT_MAX_WINDOW_BYTES = 64 * 1024
    const val MAX_WINDOW_BYTES = 1024 * 1024
    const val SUBPACKET_BYTES = 1024
    const val FALLBACK_WINDOW_BYTES = 1024
    const val WINDOW_SCALE_FACTOR = 2
    const val MAX_PACKET_BYTES = 8192
    const val MAX_INPUT_BYTES = 64 * 1024
    const val MAX_BUFFER_BYTES = 128 * 1024
    const val MAX_DETECTION_BYTES = 32
}

internal fun isFlowControl(byte: Int): Boolean = byte and ASCII_MASK == XON || byte and ASCII_MASK == XOFF
