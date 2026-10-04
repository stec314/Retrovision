// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.retrovision.app.BuildConfig
import dev.retrovision.app.Diag
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.core.backup.Sealed
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backup of the app's data, sealed with a password the user chooses: the database (still
 * SQLCipher-encrypted) and its key, every setting (secrets included, decrypted), the recordings and,
 * optionally, the offline maps.
 *
 * Why a password and not the Keystore: Keystore keys are deleted with the app, so anything sealed
 * with them is lost on uninstall. The backup is only as strong as its password (PBKDF2, 600k rounds).
 *
 * Restore is two-step: [stage] decrypts and checks the file into app-private storage (secrets
 * re-sealed with the Keystore), [applyPending] swaps it in at the next start, before the database
 * or the settings are opened.
 */
object Backup {
    private const val STAGING = "restore-staging"
    private const val READY = "READY"
    private const val FORMAT = 1
    const val MIME = "application/octet-stream"
    const val EXT = ".rvbackup"
    private const val AUTO_PREFIX = "retrovision-auto-"

    class Summary(val createdMs: Long, val appVersion: String, val dbBytes: Long, val sessions: Int, val maps: Int, val settings: Int)

    fun fileName(auto: Boolean = false): String {
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date())
        return (if (auto) AUTO_PREFIX else "retrovision-") + ts + EXT
    }

    // ------------------------------------------------------------------ export

    /** Writes a sealed backup to [out] (closed on return). Blocking: call off the main thread. */
    fun export(ctx: Context, out: OutputStream, password: CharArray, includeMaps: Boolean, progress: (String) -> Unit = {}) {
        val app = RetrovisionApp.instance
        val pass = KeyVault.passphrase(ctx, createIfMissing = false) ?: throw IOException("database key unavailable")
        val tmp = File(ctx.cacheDir, "backup-db").apply { deleteRecursively(); mkdirs() }
        try {
            // Consistent copy of the database: fold the WAL in, then copy main file + WAL while
            // holding the write lock (collection keeps queueing meanwhile). Copying to a temp file
            // first keeps the lock short; the slow part (compress + encrypt) runs without it.
            progress("database")
            val sdb = app.db.openHelper.writableDatabase
            runCatching { sdb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
            val dbVersion = sdb.version
            val dbFile = ctx.getDatabasePath(Db.NAME)
            sdb.beginTransaction()
            try {
                dbFile.copyTo(File(tmp, Db.NAME), overwrite = true)
                File(dbFile.path + "-wal").takeIf { it.exists() && it.length() > 0 }?.copyTo(File(tmp, Db.NAME + "-wal"), overwrite = true)
            } finally {
                sdb.endTransaction()
            }

            val writer = Sealed.Writer(BufferedOutputStream(out, 1 shl 16), password)
            ZipOutputStream(writer).use { zip ->
                zip.setLevel(1) // the database is encrypted, so it barely compresses: favour speed
                val sessions = SessionRecorder.dir(ctx).listFiles { f -> f.name.endsWith(".rvsl") && f.name != SessionRecorder.recording.value }.orEmpty()
                val maps = if (includeMaps) File(ctx.noBackupFilesDir, "maps").listFiles { f -> f.isFile }.orEmpty() else emptyArray()
                val settings = exportSettings(ctx)
                val manifest = JSONObject()
                    .put("format", FORMAT).put("created", System.currentTimeMillis())
                    .put("app", BuildConfig.VERSION_NAME).put("dbVersion", dbVersion)
                    .put("dbBytes", tmp.listFiles().orEmpty().sumOf { it.length() })
                    .put("sessions", sessions.size).put("maps", maps.size).put("settings", settings.length())
                put(zip, "manifest.json", manifest.toString(1).toByteArray())
                put(zip, "dbkey", pass)
                put(zip, "settings.json", settings.toString().toByteArray())
                tmp.listFiles().orEmpty().forEach { putFile(zip, "db/${it.name}", it) }
                sessions.forEachIndexed { i, f -> progress("recordings ${i + 1}/${sessions.size}"); putFile(zip, "sessions/${f.name}", f) }
                maps.forEachIndexed { i, f -> progress("maps ${i + 1}/${maps.size}"); putFile(zip, "maps/${f.name}", f) }
            }
            pass.fill(0)
            Diag.i("backup", "exported")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun put(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, name: String, f: File) {
        zip.putNextEntry(ZipEntry(name))
        f.inputStream().use { it.copyTo(zip, 1 shl 16) }
        zip.closeEntry()
    }

    /** All settings as JSON, Keystore-sealed values decrypted (they are re-sealed on restore). */
    private fun exportSettings(ctx: Context): JSONObject {
        val o = JSONObject()
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        for ((k, v) in p.all) {
            val e = JSONObject()
            when {
                k.startsWith("enc_") && v is String -> {
                    val plain = KeyVault.open(v) ?: continue // unreadable: nothing to carry over
                    e.put("t", "secret").put("v", plain); o.put(k.removePrefix("enc_"), e); continue
                }
                v is Boolean -> e.put("t", "b").put("v", v)
                v is Int -> e.put("t", "i").put("v", v)
                v is Long -> e.put("t", "l").put("v", v)
                v is Float -> e.put("t", "f").put("v", v.toDouble())
                v is String -> e.put("t", "s").put("v", v)
                v is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, e)
        }
        return o
    }

    // ------------------------------------------------------------------ restore

    /**
     * Decrypts and checks a backup into staging. Nothing current is touched; call [commit] to have it
     * applied at the next start. Wrong password and damaged files throw [Sealed.BadBackup].
     */
    fun stage(ctx: Context, input: InputStream, password: CharArray, progress: (String) -> Unit = {}): Summary {
        val dir = File(ctx.filesDir, STAGING).apply { deleteRecursively(); mkdirs() }
        try {
            var manifest: JSONObject? = null
            var hasKey = false
            var hasDb = false
            var sessions = 0
            var maps = 0
            var settingsCount = 0
            ZipInputStream(Sealed.Reader(BufferedInputStream(input, 1 shl 16), password)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    // Only the entries we write, never a path that escapes staging.
                    if (name.contains("..") || name.startsWith("/")) throw IOException("unexpected entry $name")
                    when {
                        name == "manifest.json" -> manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        name == "dbkey" -> {
                            val k = zip.readBytes()
                            val sealed = KeyVault.seal(String(k, Charsets.US_ASCII)) ?: throw IOException("Keystore unavailable")
                            k.fill(0)
                            File(dir, "dbkey.sealed").writeText(sealed)
                            hasKey = true
                        }
                        name == "settings.json" -> {
                            val json = zip.readBytes().toString(Charsets.UTF_8)
                            settingsCount = JSONObject(json).length()
                            val sealed = KeyVault.seal(json) ?: throw IOException("Keystore unavailable")
                            File(dir, "settings.sealed").writeText(sealed)
                        }
                        name.startsWith("db/") || name.startsWith("sessions/") || name.startsWith("maps/") -> {
                            val leaf = name.substringAfter('/')
                            if (leaf.isEmpty() || leaf.contains('/')) throw IOException("unexpected entry $name")
                            val sub = File(dir, name.substringBefore('/')).apply { mkdirs() }
                            progress(name)
                            File(sub, leaf).outputStream().use { zip.copyTo(it, 1 shl 16) }
                            when {
                                name.startsWith("db/") -> if (leaf == Db.NAME) hasDb = true
                                name.startsWith("sessions/") -> sessions++
                                else -> maps++
                            }
                        }
                        else -> Unit // unknown entry from a newer version: ignored
                    }
                }
            }
            val m = manifest ?: throw IOException("backup has no manifest")
            if (m.optInt("format") > FORMAT) throw IOException("backup made by a newer version of the app: update first")
            if (!hasKey || !hasDb) throw IOException("backup is incomplete")
            val current = runCatching { RetrovisionApp.instance.db.openHelper.readableDatabase.version }.getOrDefault(Int.MAX_VALUE)
            if (m.optInt("dbVersion") > current) throw IOException("backup made by a newer version of the app: update first")
            return Summary(m.optLong("created"), m.optString("app"), m.optLong("dbBytes"), sessions, maps, settingsCount)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Marks the staged backup to be applied at the next start. */
    fun commit(ctx: Context) {
        File(File(ctx.filesDir, STAGING), READY).writeText("1")
    }

    fun discardStaged(ctx: Context) {
        File(ctx.filesDir, STAGING).deleteRecursively()
    }

    /**
     * Called first thing at start-up, before the database or the settings are opened: replaces the
     * current data with a committed backup. A failure leaves the current data in place.
     */
    fun applyPending(ctx: Context) {
        val dir = File(ctx.filesDir, STAGING)
        if (!File(dir, READY).exists()) {
            if (dir.exists()) dir.deleteRecursively() // staged but never confirmed
            return
        }
        try {
            val key = KeyVault.open(File(dir, "dbkey.sealed").readText()) ?: throw IOException("cannot unseal the database key")
            val settings = File(dir, "settings.sealed").takeIf { it.exists() }?.let { KeyVault.open(it.readText()) ?: throw IOException("cannot unseal settings") }
            val dbDir = File(dir, "db")
            if (!File(dbDir, Db.NAME).exists()) throw IOException("no database in the backup")

            // Database: drop the current one, move the backup's files in, install its key.
            ctx.deleteDatabase(Db.NAME)
            val target = ctx.getDatabasePath(Db.NAME).apply { parentFile?.mkdirs() }
            dbDir.listFiles().orEmpty().forEach { f ->
                val dest = File(target.parentFile, f.name)
                if (!f.renameTo(dest)) { f.copyTo(dest, overwrite = true); f.delete() }
            }
            if (!KeyVault.importPassphrase(ctx, key.toByteArray(Charsets.US_ASCII))) throw IOException("cannot store the database key")

            // Settings: replace all, re-sealing the secrets with this install's Keystore.
            if (settings != null) {
                val o = JSONObject(settings)
                val ed = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear()
                for (k in o.keys()) {
                    val e = o.getJSONObject(k)
                    when (e.getString("t")) {
                        "secret" -> KeyVault.seal(e.getString("v"))?.let { ed.putString("enc_$k", it) }
                        "b" -> ed.putBoolean(k, e.getBoolean("v"))
                        "i" -> ed.putInt(k, e.getInt("v"))
                        "l" -> ed.putLong(k, e.getLong("v"))
                        "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                        "s" -> ed.putString(k, e.getString("v"))
                        "set" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                    }
                }
                ed.commit()
            }

            // Recordings (sealed with a key derived from the database key, which came along).
            File(dir, "sessions").listFiles()?.let { files ->
                val sd = File(ctx.filesDir, "sessions").apply { mkdirs() }
                files.forEach { f -> val d = File(sd, f.name); if (!f.renameTo(d)) { f.copyTo(d, overwrite = true); f.delete() } }
            }
            File(dir, "maps").listFiles()?.let { files ->
                val md = File(ctx.noBackupFilesDir, "maps").apply { mkdirs() }
                files.forEach { f -> val d = File(md, f.name); if (!f.renameTo(d)) { f.copyTo(d, overwrite = true); f.delete() } }
            }
            Diag.i("backup", "restored")
        } catch (e: Exception) {
            Diag.e("backup", "restore failed", e)
            File(ctx.filesDir, "restore-failed.txt").writeText(e.message ?: e.javaClass.simpleName)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Message of a restore that failed at start-up, once. */
    fun takeRestoreError(ctx: Context): String? {
        val f = File(ctx.filesDir, "restore-failed.txt")
        if (!f.exists()) return null
        return f.readText().also { f.delete() }
    }

    // ------------------------------------------------------------------ automatic backups

    private val autoRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    val autoState = kotlinx.coroutines.flow.MutableStateFlow("")

    /** True if the chosen folder is still writable by this install (permissions do not survive a reinstall). */
    fun folderUsable(ctx: Context, tree: String): Boolean =
        tree.isNotEmpty() && ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isWritePermission }

    /** Runs an automatic backup if one is due. Blocking; safe to call from several places. */
    fun runIfDue(ctx: Context, force: Boolean = false) {
        val prefs = RetrovisionApp.instance.prefs
        val tree = prefs.autoBackupTree
        if (tree.isEmpty()) return
        val pw = prefs.autoBackupPassword
        if (pw.isEmpty()) return
        val due = prefs.lastAutoBackupMs + prefs.autoBackupDays * 86_400_000L
        if (!force && System.currentTimeMillis() < due) return
        if (!autoRunning.compareAndSet(false, true)) return
        try {
            if (!folderUsable(ctx, tree)) {
                prefs.lastAutoBackupError = "No access to the backup folder any more: choose it again"
                return
            }
            autoState.value = "running"
            autoBackup(ctx, Uri.parse(tree), pw.toCharArray(), prefs.autoBackupMaps)
            prefs.lastAutoBackupMs = System.currentTimeMillis()
            prefs.lastAutoBackupError = ""
        } catch (e: Exception) {
            Diag.e("backup", "automatic backup failed", e)
            prefs.lastAutoBackupError = e.message ?: e.javaClass.simpleName
        } finally {
            autoState.value = ""
            autoRunning.set(false)
        }
    }

    /**
     * Writes a backup into the folder the user picked ([tree], persisted permission) and keeps the
     * newest [keep] automatic ones. The folder lives outside the app, so it survives an uninstall.
     */
    fun autoBackup(ctx: Context, tree: Uri, password: CharArray, includeMaps: Boolean, keep: Int = 2) {
        val cr = ctx.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(cr, parent, MIME, fileName(auto = true)) ?: throw IOException("cannot create the backup file in the chosen folder")
        try {
            cr.openOutputStream(doc)?.use { export(ctx, it, password, includeMaps) } ?: throw IOException("cannot write to the chosen folder")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(cr, doc) }
            throw e
        }
        // Rotate: keep the newest [keep] automatic backups, never touch other files.
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val autos = mutableListOf<Pair<String, String>>()
        cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(AUTO_PREFIX) && name.endsWith(EXT)) autos += c.getString(0) to name
            }
        }
        autos.sortedByDescending { it.second }.drop(keep).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }
}
