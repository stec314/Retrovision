// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

class SealedTest {
    private val pw = "correct horse battery".toCharArray()

    private fun seal(data: ByteArray, chunk: Int = 1000): ByteArray {
        val bo = ByteArrayOutputStream()
        Sealed.Writer(bo, pw.copyOf(), iterations = 10_000, chunkSize = chunk).use { it.write(data) }
        return bo.toByteArray()
    }

    private fun open(sealed: ByteArray, p: CharArray = pw.copyOf()) = Sealed.Reader(ByteArrayInputStream(sealed), p).readBytes()

    private fun expectBad(block: () -> Unit) {
        try { block(); fail("expected BadBackup") } catch (_: Sealed.BadBackup) {}
    }

    @Test fun roundTripAcrossChunkBoundaries() {
        val r = Random(1)
        for (size in listOf(0, 1, 999, 1000, 1001, 5000, 12_345)) {
            val data = ByteArray(size).also(r::nextBytes)
            assertArrayEquals("size $size", data, open(seal(data)))
        }
    }

    @Test fun wrongPasswordFails() {
        val s = seal(ByteArray(3000) { it.toByte() })
        expectBad { open(s, "wrong password!!".toCharArray()) }
    }

    @Test fun truncationIsDetected() {
        val s = seal(ByteArray(3500) { it.toByte() })
        // Cut on a chunk boundary (drops the final chunk) and mid-chunk.
        val chunkBytes = 1 + 4 + 1000 + 16
        expectBad { open(s.copyOf(Sealed.HEADER + 3 * chunkBytes)) }
        expectBad { open(s.copyOf(s.size - 7)) }
    }

    @Test fun tamperingIsDetected() {
        val s = seal(ByteArray(2500) { it.toByte() })
        val t = s.copyOf().also { it[Sealed.HEADER + 40] = (it[Sealed.HEADER + 40].toInt() xor 1).toByte() }
        expectBad { open(t) }
        // Flipping a chunk's "final" flag breaks its AAD.
        val f = s.copyOf().also { it[Sealed.HEADER] = 1 }
        expectBad { open(f) }
    }

    @Test fun rejectsOtherFiles() {
        expectBad { open("PK\u0003\u0004 not a backup at all, just some zip-looking bytes".toByteArray()) }
        assertTrue(Sealed.looksSealed(seal(ByteArray(1))))
    }
}
