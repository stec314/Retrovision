package dev.retrovision.app.data

import android.content.Context
import dev.retrovision.core.session.ChunkCrypt
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Session recordings on disk are encrypted (AES-256-GCM chunks, key derived from the database
 * passphrase). Exports are plain, so a recording can be replayed on another phone; imports are
 * encrypted on arrival. Recordings written by older versions in clear are encrypted in place.
 */
object SessionFiles {
    @Volatile private var key: ByteArray? = null

    private fun key(ctx: Context): ByteArray =
        key ?: (KeyVault.sessionKey(ctx) ?: throw IOException("Keystore unavailable: not writing recordings unencrypted"))
            .also { key = it }

    fun openWrite(ctx: Context, f: File): OutputStream = ChunkCrypt.Writer(FileOutputStream(f), key(ctx))

    /** Plain recording bytes, whatever the file's format on disk. */
    fun openRead(ctx: Context, f: File): InputStream {
        val input = BufferedInputStream(f.inputStream())
        input.mark(8)
        val head = ByteArray(4)
        val n = input.read(head)
        input.reset()
        return if (n == 4 && ChunkCrypt.isEncrypted(head)) ChunkCrypt.Reader(input, key(ctx)) else input
    }

    fun isEncrypted(f: File): Boolean = runCatching {
        f.inputStream().use { val h = ByteArray(4); it.read(h) == 4 && ChunkCrypt.isEncrypted(h) }
    }.getOrDefault(false)

    /** Stores an imported (plain) recording encrypted. */
    fun import(ctx: Context, src: InputStream, dest: File) {
        openWrite(ctx, dest).use { o -> src.copyTo(o) }
    }

    /** Writes the plain recording to [out] (for sharing with another phone). */
    fun export(ctx: Context, f: File, out: OutputStream) {
        openRead(ctx, f).use { it.copyTo(out) }
    }

    /** Encrypts recordings left in clear by older versions. Skips the one being written. */
    fun encryptLegacy(ctx: Context, skip: String?) {
        for (f in SessionRecorder.dir(ctx).listFiles { x -> x.name.endsWith(".rvsl") }.orEmpty()) {
            if (f.name == skip || isEncrypted(f)) continue
            val tmp = File(f.parentFile, f.name + ".tmp")
            runCatching {
                f.inputStream().use { i -> openWrite(ctx, tmp).use { o -> i.copyTo(o) } }
                if (!tmp.renameTo(f)) throw IOException("rename failed")
            }.onFailure { tmp.delete() }
        }
    }
}
