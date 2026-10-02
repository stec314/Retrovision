package dev.retrovision.core.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class ChunkCryptTest {
    private val key = ByteArray(32) { it.toByte() }
    private val data = ByteArray(200_000) { (it * 31 % 251).toByte() }

    private fun encrypt(flushEvery: Int = 7_000): ByteArray {
        val out = ByteArrayOutputStream()
        ChunkCrypt.Writer(out, key).use { w ->
            var i = 0
            while (i < data.size) {
                val n = minOf(flushEvery, data.size - i)
                w.write(data, i, n); w.flush(); i += n
            }
        }
        return out.toByteArray()
    }

    @Test fun roundTripAcrossManyFlushes() {
        val enc = encrypt()
        assertTrue(ChunkCrypt.isEncrypted(enc))
        assertArrayEquals(data, ChunkCrypt.Reader(ByteArrayInputStream(enc), key).readBytes())
    }

    @Test fun gzipInsideWorks() {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(ChunkCrypt.Writer(out, key), 8192, true).use { it.write(data) }
        val back = GZIPInputStream(ChunkCrypt.Reader(ByteArrayInputStream(out.toByteArray()), key)).readBytes()
        assertArrayEquals(data, back)
    }

    @Test fun tornTailKeepsCompleteChunks() {
        val enc = encrypt()
        val torn = enc.copyOf(enc.size - 100)
        val back = ChunkCrypt.Reader(ByteArrayInputStream(torn), key).readBytes()
        assertTrue(back.isNotEmpty() && back.size < data.size)
        assertArrayEquals(data.copyOf(back.size), back)
    }

    @Test fun wrongKeyOrTamperingIsDetected() {
        val enc = encrypt()
        try {
            ChunkCrypt.Reader(ByteArrayInputStream(enc), ByteArray(32)).readBytes()
            fail("wrong key accepted")
        } catch (_: IOException) {}
        val bad = enc.copyOf().also { it[60] = (it[60].toInt() xor 1).toByte() }
        try {
            ChunkCrypt.Reader(ByteArrayInputStream(bad), key).readBytes()
            fail("tampering accepted")
        } catch (_: IOException) {}
    }

    @Test fun plaintextIsNotDetectedAsEncrypted() {
        assertTrue(!ChunkCrypt.isEncrypted(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0)))
    }
}
