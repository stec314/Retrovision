// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.wire

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Retrovision wire framing (docs/protocol.md §3):
 *
 *     frame = COBS(envelope || CRC32_LE(envelope)) || 0x00
 *
 * Must reproduce proto/testvectors/framing.json byte for byte.
 */
object Framing {
    const val CRC_LEN = 4
    const val MAX_DECODED_FRAME = 1280
    const val MAX_ENCODED_FRAME = MAX_DECODED_FRAME + MAX_DECODED_FRAME / 254 + 1
    const val MAX_ENVELOPE = MAX_DECODED_FRAME - CRC_LEN

    fun crc32(data: ByteArray, off: Int = 0, len: Int = data.size): Long =
        CRC32().apply { update(data, off, len) }.value

    fun cobsEncode(input: ByteArray): ByteArray {
        val out = ByteArray(input.size + input.size / 254 + 1)
        var codeIdx = 0
        var o = 1
        var code = 1
        for (b in input) {
            if (b.toInt() == 0) {
                out[codeIdx] = code.toByte()
                codeIdx = o++
                code = 1
            } else {
                out[o++] = b
                if (++code == 0xFF) {
                    out[codeIdx] = code.toByte()
                    codeIdx = o++
                    code = 1
                }
            }
        }
        out[codeIdx] = code.toByte()
        return out.copyOf(o)
    }

    /** @throws FrameException on malformed input. */
    fun cobsDecode(input: ByteArray, off: Int = 0, len: Int = input.size): ByteArray {
        val out = ByteArrayOutputStream(len)
        var i = off
        val end = off + len
        while (i < end) {
            val code = input[i].toInt() and 0xFF
            if (code == 0) throw FrameException("zero byte inside COBS block")
            i++
            val blockEnd = i + code - 1
            if (blockEnd > end) throw FrameException("COBS block overruns frame")
            while (i < blockEnd) {
                val b = input[i++]
                if (b.toInt() == 0) throw FrameException("zero byte inside COBS block")
                out.write(b.toInt())
            }
            if (code != 0xFF && i < end) out.write(0)
        }
        return out.toByteArray()
    }

    /** Envelope bytes -> complete frame including the trailing delimiter. */
    fun encode(envelope: ByteArray): ByteArray {
        require(envelope.size <= MAX_ENVELOPE) { "envelope too large: ${envelope.size}" }
        val crc = crc32(envelope)
        val payload = envelope.copyOf(envelope.size + CRC_LEN)
        for (k in 0 until CRC_LEN) payload[envelope.size + k] = (crc ushr (8 * k)).toByte()
        val enc = cobsEncode(payload)
        return enc.copyOf(enc.size + 1) // trailing 0x00
    }

    /** One encoded frame WITHOUT its delimiter -> envelope bytes. */
    fun decode(encoded: ByteArray, off: Int = 0, len: Int = encoded.size): ByteArray {
        if (len > MAX_ENCODED_FRAME) throw FrameException("encoded frame too large")
        val payload = cobsDecode(encoded, off, len)
        if (payload.size < CRC_LEN + 1) throw FrameException("frame too short")
        val body = payload.size - CRC_LEN
        var want = 0L
        for (k in 0 until CRC_LEN) want = want or ((payload[body + k].toLong() and 0xFF) shl (8 * k))
        if (crc32(payload, 0, body) != want) throw FrameException("CRC mismatch")
        return payload.copyOf(body)
    }
}

class FrameException(message: String) : Exception(message)

/**
 * Incremental decoder for a byte stream. Feed arbitrary chunks, get complete
 * envelopes back. Bad frames are counted and skipped; the decoder always
 * resynchronises on the next 0x00. Not thread-safe: one per connection.
 */
class FrameDecoder {
    private val buf = ByteArray(Framing.MAX_ENCODED_FRAME)
    private var len = 0
    private var overflow = false

    var badFrames: Long = 0
        private set

    fun feed(chunk: ByteArray, off: Int = 0, count: Int = chunk.size, onEnvelope: (ByteArray) -> Unit) {
        for (i in off until off + count) {
            val b = chunk[i]
            if (b.toInt() != 0) {
                if (overflow) continue
                if (len >= buf.size) {
                    overflow = true
                    len = 0
                } else {
                    buf[len++] = b
                }
                continue
            }
            // Delimiter.
            when {
                overflow -> badFrames++
                len > 0 -> try {
                    onEnvelope(Framing.decode(buf, 0, len))
                } catch (_: FrameException) {
                    badFrames++
                }
                // len == 0: empty frame, legal and ignored
            }
            len = 0
            overflow = false
        }
    }

    fun reset() {
        len = 0
        overflow = false
    }
}
