// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.session

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption for append-only files (session recordings), AES-256-GCM in independent chunks.
 *
 * Why chunks: a recording is written for hours and must survive a crash, so it is flushed often;
 * one GCM stream over the whole file could not be verified until the very end and would have to
 * be held in memory to decrypt. Each chunk is authenticated on its own, and its index is bound
 * into the tag (AAD), so chunks cannot be reordered or spliced between files without detection.
 * A crash can only lose the unflushed tail.
 *
 * Layout: "RVSE" | version(1) | file id(16) | { length(4) | nonce(12) | ciphertext+tag }*
 */
object ChunkCrypt {
    val MAGIC = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte())
    private const val VERSION = 1
    private const val FILE_ID = 16
    private const val NONCE = 12
    private const val TAG_BITS = 128
    const val CHUNK = 64 * 1024
    private val rng = SecureRandom()

    fun isEncrypted(header: ByteArray): Boolean = header.size >= 4 && header.copyOfRange(0, 4).contentEquals(MAGIC)

    private fun aad(fileId: ByteArray, index: Long): ByteArray =
        fileId + ByteArray(8) { ((index ushr (56 - 8 * it)) and 0xFF).toByte() }

    class Writer(private val out: OutputStream, key: ByteArray) : OutputStream() {
        private val k = SecretKeySpec(key, "AES")
        private val buf = ByteArrayOutputStream(CHUNK)
        private val fileId = ByteArray(FILE_ID).also { rng.nextBytes(it) }
        private val d = DataOutputStream(out)
        private var index = 0L
        private var closed = false

        init {
            require(key.size == 32) { "AES-256 key required" }
            d.write(MAGIC)
            d.writeByte(VERSION)
            d.write(fileId)
        }

        override fun write(b: Int) {
            buf.write(b)
            if (buf.size() >= CHUNK) emit()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var n = len
            while (n > 0) {
                val take = minOf(n, CHUNK - buf.size())
                buf.write(b, o, take)
                o += take; n -= take
                if (buf.size() >= CHUNK) emit()
            }
        }

        private fun emit() {
            if (buf.size() == 0) return
            val nonce = ByteArray(NONCE).also { rng.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce))
            c.updateAAD(aad(fileId, index++))
            val ct = c.doFinal(buf.toByteArray())
            buf.reset()
            d.writeInt(ct.size)
            d.write(nonce)
            d.write(ct)
        }

        override fun flush() {
            emit()
            d.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            flush()
            out.close()
        }
    }

    /** Reads a file written by [Writer]. A torn final chunk (crash while writing) ends the stream cleanly. */
    class Reader(input: InputStream, key: ByteArray) : InputStream() {
        private val k = SecretKeySpec(key, "AES")
        private val d = DataInputStream(input)
        private val fileId = ByteArray(FILE_ID)
        private var cur = ByteArray(0)
        private var pos = 0
        private var index = 0L
        private var eof = false

        init {
            val magic = ByteArray(4)
            d.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw IOException("not an encrypted recording")
            if (d.readUnsignedByte() != VERSION) throw IOException("unsupported recording encryption version")
            d.readFully(fileId)
        }

        private fun next(): Boolean {
            if (eof) return false
            val len = try { d.readInt() } catch (_: EOFException) { eof = true; return false }
            if (len <= 0 || len > CHUNK + 64) throw IOException("corrupt recording")
            val nonce = ByteArray(NONCE)
            val ct = ByteArray(len)
            try {
                d.readFully(nonce)
                d.readFully(ct)
            } catch (_: EOFException) {
                eof = true // torn tail after a crash: keep what was complete
                return false
            }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce))
            c.updateAAD(aad(fileId, index++))
            cur = try { c.doFinal(ct) } catch (e: Exception) { throw IOException("recording failed authentication (wrong key or tampered)", e) }
            pos = 0
            return true
        }

        override fun read(): Int {
            while (pos >= cur.size) if (!next()) return -1
            return cur[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= cur.size) if (!next()) return -1
            val n = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = d.close()
    }
}
