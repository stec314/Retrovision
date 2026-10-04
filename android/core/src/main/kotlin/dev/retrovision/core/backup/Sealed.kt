// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.backup

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-sealed stream for backups: PBKDF2-HMAC-SHA256 → AES-256-GCM, in independent chunks so
 * a backup of any size is written and read in constant memory (Android's GCM buffers a whole
 * message on decrypt).
 *
 * Layout: header = "RVBK" | version(1) | iterations(4, BE) | salt(16) | noncePrefix(8);
 * then chunks = final(1) | length(4, BE) | ciphertext+tag. Nonce = prefix | chunk index(4, BE).
 * AAD = header | chunk index | final flag, so chunks cannot be reordered, dropped, or the stream
 * cut short without the reader noticing (it requires a final chunk).
 */
object Sealed {
    const val VERSION: Byte = 1
    const val DEFAULT_ITERATIONS = 600_000
    private val MAGIC = "RVBK".toByteArray(Charsets.US_ASCII)
    internal const val HEADER = 4 + 1 + 4 + 16 + 8
    private const val TAG_BYTES = 16
    private const val MAX_CHUNK = 4 shl 20

    internal fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(k, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    internal fun nonce(prefix: ByteArray, index: Int) = ByteBuffer.allocate(12).put(prefix).putInt(index).array()

    internal fun aad(header: ByteArray, index: Int, final: Boolean) =
        ByteBuffer.allocate(header.size + 5).put(header).putInt(index).put(if (final) 1 else 0).array()

    /** True if [head] (at least 4 bytes) starts like a sealed backup. */
    fun looksSealed(head: ByteArray) = head.size >= 4 && head.copyOf(4).contentEquals(MAGIC)

    class Writer(
        private val out: OutputStream,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        private val chunkSize: Int = 1 shl 20,
        random: SecureRandom = SecureRandom(),
    ) : OutputStream() {
        private val header: ByteArray
        private val prefix = ByteArray(8)
        private val key: SecretKeySpec
        private val buf = ByteArray(chunkSize)
        private var pos = 0
        private var index = 0
        private var closed = false

        init {
            require(chunkSize in 1..MAX_CHUNK)
            val salt = ByteArray(16).also(random::nextBytes)
            random.nextBytes(prefix)
            header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(prefix).array()
            key = deriveKey(password, salt, iterations)
            out.write(header)
        }

        override fun write(b: Int) {
            if (pos == chunkSize) emit(false)
            buf[pos++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var n = len
            while (n > 0) {
                if (pos == chunkSize) emit(false)
                val k = minOf(n, chunkSize - pos)
                System.arraycopy(b, o, buf, pos, k)
                pos += k; o += k; n -= k
            }
        }

        private fun emit(final: Boolean) {
            check(index != -1) { "too many chunks" }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, index)))
            c.updateAAD(aad(header, index, final))
            val ct = c.doFinal(buf, 0, pos)
            out.write(if (final) 1 else 0)
            out.write(ByteBuffer.allocate(4).putInt(ct.size).array())
            out.write(ct)
            pos = 0
            index++
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            emit(true)
            out.close()
        }
    }

    /** Thrown for a wrong password or a damaged/truncated file (GCM cannot tell them apart). */
    class BadBackup(msg: String) : IOException(msg)

    class Reader(input: InputStream, password: CharArray) : InputStream() {
        private val inp = DataInputStream(input)
        private val header = ByteArray(HEADER)
        private val prefix: ByteArray
        private val key: SecretKeySpec
        private var cur = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var done = false

        init {
            try {
                inp.readFully(header)
            } catch (_: EOFException) {
                throw BadBackup("not a Retrovision backup (too short)")
            }
            if (!looksSealed(header)) throw BadBackup("not a Retrovision backup")
            val bb = ByteBuffer.wrap(header, 4, HEADER - 4)
            val ver = bb.get()
            if (ver != VERSION) throw BadBackup("backup format v$ver not supported")
            val iterations = bb.int
            if (iterations !in 10_000..10_000_000) throw BadBackup("damaged header")
            val salt = ByteArray(16).also { bb.get(it) }
            prefix = ByteArray(8).also { bb.get(it) }
            key = deriveKey(password, salt, iterations)
        }

        private fun next(): Boolean {
            if (done) return false
            val flag = try {
                inp.readUnsignedByte()
            } catch (_: EOFException) {
                throw BadBackup("backup is truncated")
            }
            if (flag > 1) throw BadBackup("damaged backup")
            val len = try { inp.readInt() } catch (_: EOFException) { throw BadBackup("backup is truncated") }
            if (len < TAG_BYTES || len > MAX_CHUNK + TAG_BYTES) throw BadBackup("damaged backup")
            val ct = ByteArray(len)
            try { inp.readFully(ct) } catch (_: EOFException) { throw BadBackup("backup is truncated") }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, index)))
            c.updateAAD(aad(header, index, flag == 1))
            cur = try {
                c.doFinal(ct)
            } catch (_: javax.crypto.AEADBadTagException) {
                throw BadBackup(if (index == 0) "wrong password, or the file is damaged" else "backup is damaged")
            }
            pos = 0
            index++
            if (flag == 1) {
                done = true
                if (inp.read() != -1) throw BadBackup("unexpected data after the end of the backup")
            }
            return true
        }

        override fun read(): Int {
            while (pos == cur.size) if (!next()) return -1
            return cur[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos == cur.size) if (!next()) return -1
            val n = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = inp.close()
    }
}
