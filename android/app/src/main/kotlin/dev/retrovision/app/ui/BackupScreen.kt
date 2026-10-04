// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.retrovision.app.RetrovisionApp
import dev.retrovision.app.data.Backup
import dev.retrovision.app.service.CollectorService
import dev.retrovision.core.backup.Sealed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val app get() = RetrovisionApp.instance
private const val MIN_PASSWORD = 10

@Composable
internal fun backupSummary(): String {
    val p = app.prefs
    return when {
        p.autoBackupTree.isEmpty() -> Texts.tr("Automatic backup off", "Backup automatico spento")
        p.lastAutoBackupError.isNotEmpty() -> Texts.tr("Automatic backup failing", "Backup automatico in errore")
        p.lastAutoBackupMs == 0L -> Texts.tr("Automatic backup on, none yet", "Backup automatico attivo, nessuno ancora")
        else -> Texts.tr("Last automatic backup ", "Ultimo backup automatico ") +
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.lastAutoBackupMs))
    }
}

/** Restarts the app so a committed restore is applied before anything opens the database. */
private fun restartApp(ctx: Context) {
    CollectorService.stop(ctx)
    val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        ctx.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }, 800)
}

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun passwordProblem(pw: String, again: String): String? = when {
    pw.length < MIN_PASSWORD -> Texts.tr("At least $MIN_PASSWORD characters.", "Almeno $MIN_PASSWORD caratteri.")
    pw != again -> Texts.tr("The two passwords differ.", "Le due password non coincidono.")
    else -> null
}

/** Export, restore and automatic backup (Settings → Backup and restore). */
@Composable
fun BackupSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = app.prefs
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(Backup.takeRestoreError(ctx)?.let { Texts.tr("The last restore failed: ", "L'ultimo ripristino è fallito: ") + it }) }

    // ---- manual export ----
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var withMaps by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val chars = pw.toCharArray()
        scope.launch {
            busy = Texts.tr("Writing the backup…", "Scrittura del backup…"); error = null; message = null
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { Backup.export(ctx, it, chars, withMaps) { s -> busy = s } }
                        ?: throw java.io.IOException("cannot open the file")
                }
            }
            chars.fill(' ')
            busy = null
            r.onSuccess { message = Texts.tr("Backup written. Keep the password: without it the file cannot be opened.", "Backup scritto. Conserva la password: senza non si può aprire il file."); pw = ""; pw2 = "" }
                .onFailure { e -> runCatching { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, uri) }; error = e.message ?: e.javaClass.simpleName }
        }
    }

    // ---- restore ----
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePw by remember { mutableStateOf("") }
    var staged by remember { mutableStateOf<Backup.Summary?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { restoreUri = uri; restorePw = "" } }

    // ---- automatic ----
    var autoTree by remember { mutableStateOf(prefs.autoBackupTree) }
    var autoPw by remember { mutableStateOf("") }
    var autoPw2 by remember { mutableStateOf("") }
    var autoDays by remember { mutableStateOf(prefs.autoBackupDays) }
    var autoMaps by remember { mutableStateOf(prefs.autoBackupMaps) }
    val autoRunning by Backup.autoState.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
            .onSuccess {
                // Release the previous folder's grant, if any.
                prefs.autoBackupTree.takeIf { it.isNotEmpty() && it != uri.toString() }?.let { old ->
                    runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
                }
                if (prefs.autoBackupPassword.isEmpty() && passwordProblem(autoPw, autoPw2) == null) prefs.autoBackupPassword = autoPw
                prefs.autoBackupTree = uri.toString(); autoTree = uri.toString()
                prefs.lastAutoBackupError = ""; prefs.lastAutoBackupMs = 0L
                autoPw = ""; autoPw2 = ""
                refresh++
                scope.launch(Dispatchers.IO) { Backup.runIfDue(ctx, force = true); refresh++ }
            }
            .onFailure { error = it.message }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            Texts.tr(
                "Uninstalling deletes everything, including the key of the encrypted database: nothing can be recovered afterwards. To update, install the new APK over the old one — no need to uninstall, data stays. A backup is sealed with a password you choose and can be restored on this or another phone.",
                "Disinstallare cancella tutto, compresa la chiave del database cifrato: dopo non si recupera nulla. Per aggiornare installa il nuovo APK sopra il vecchio — non serve disinstallare, i dati restano. Il backup è sigillato con una password scelta da te e si può ripristinare su questo o un altro telefono.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            Texts.tr(
                "It contains your places, routes and the addresses of devices around you: whoever has the file and the password sees all of it. Lost password = unreadable backup.",
                "Contiene i tuoi luoghi, i percorsi e gli indirizzi dei dispositivi intorno a te: chi ha il file e la password vede tutto. Password persa = backup illeggibile.",
            ),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
        busy?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(it, style = MaterialTheme.typography.labelSmall)
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        // ------------------------------------------------ automatic
        Text(Texts.tr("Automatic backup", "Backup automatico"), style = MaterialTheme.typography.titleSmall)
        Text(
            Texts.tr(
                "Writes a backup on a schedule into a folder outside the app (e.g. Documents, or a cloud drive folder), keeping the last two. It survives an uninstall.",
                "Scrive un backup periodico in una cartella fuori dall'app (es. Documenti, o una cartella del cloud), tenendo gli ultimi due. Sopravvive alla disinstallazione.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        remember(refresh) { 0 }
        if (autoTree.isEmpty()) {
            PasswordField(autoPw, Texts.tr("Backup password", "Password del backup")) { autoPw = it }
            PasswordField(autoPw2, Texts.tr("Repeat password", "Ripeti la password")) { autoPw2 = it }
            val problem = passwordProblem(autoPw, autoPw2)
            if (autoPw.isNotEmpty() && problem != null) Text(problem, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            Button(enabled = problem == null && busy == null, onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(Texts.tr("Choose folder and turn on", "Scegli la cartella e attiva"))
            }
        } else {
            val usable = Backup.folderUsable(ctx, autoTree)
            val folderName = Uri.parse(autoTree).lastPathSegment?.substringAfter(':') ?: autoTree
            Text(Texts.tr("Folder: ", "Cartella: ") + folderName, style = MaterialTheme.typography.bodyMedium)
            if (!usable) Text(
                Texts.tr("This install has no access to that folder (for example after a reinstall or restore): choose it again.", "Questa installazione non ha accesso alla cartella (per esempio dopo una reinstallazione o un ripristino): sceglila di nuovo."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
            Text(
                when {
                    autoRunning.isNotEmpty() -> Texts.tr("Backing up now…", "Backup in corso…")
                    prefs.lastAutoBackupMs == 0L -> Texts.tr("No backup yet.", "Nessun backup ancora.")
                    else -> Texts.tr("Last: ", "Ultimo: ") + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(prefs.lastAutoBackupMs))
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (prefs.lastAutoBackupError.isNotEmpty()) Text(prefs.lastAutoBackupError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = autoDays == 1, onClick = { autoDays = 1; prefs.autoBackupDays = 1 }, label = { Text(Texts.tr("Daily", "Ogni giorno")) })
                FilterChip(selected = autoDays == 7, onClick = { autoDays = 7; prefs.autoBackupDays = 7 }, label = { Text(Texts.tr("Weekly", "Ogni settimana")) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoMaps, onCheckedChange = { autoMaps = it; prefs.autoBackupMaps = it })
                Text(Texts.tr("Include offline maps (large)", "Includi le mappe offline (pesanti)"), style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = usable && autoRunning.isEmpty(), onClick = { scope.launch(Dispatchers.IO) { Backup.runIfDue(ctx, force = true); refresh++ } }) {
                    Text(Texts.tr("Back up now", "Backup adesso"))
                }
                if (!usable) OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(Texts.tr("Choose folder", "Scegli cartella")) }
                TextButton(onClick = {
                    runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(autoTree), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    prefs.autoBackupTree = ""; prefs.autoBackupPassword = ""; prefs.lastAutoBackupError = ""; prefs.lastAutoBackupMs = 0L
                    autoTree = ""
                }) { Text(Texts.tr("Turn off", "Disattiva")) }
            }
        }

        HorizontalDivider()
        // ------------------------------------------------ manual export
        Text(Texts.tr("Export now", "Esporta adesso"), style = MaterialTheme.typography.titleSmall)
        PasswordField(pw, Texts.tr("Password for this file", "Password per questo file")) { pw = it }
        PasswordField(pw2, Texts.tr("Repeat password", "Ripeti la password")) { pw2 = it }
        val problem = passwordProblem(pw, pw2)
        if (pw.isNotEmpty() && problem != null) Text(problem, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = withMaps, onCheckedChange = { withMaps = it })
            Text(Texts.tr("Include offline maps (large)", "Includi le mappe offline (pesanti)"), style = MaterialTheme.typography.bodyMedium)
        }
        Button(enabled = problem == null && busy == null, onClick = { exporter.launch(Backup.fileName()) }, modifier = Modifier.fillMaxWidth()) {
            Text(Texts.tr("Export backup…", "Esporta backup…"))
        }

        HorizontalDivider()
        // ------------------------------------------------ restore
        Text(Texts.tr("Restore", "Ripristina"), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(enabled = busy == null, onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text(Texts.tr("Restore from a backup…", "Ripristina da un backup…"))
        }
    }

    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text(Texts.tr("Backup password", "Password del backup")) },
            text = { PasswordField(restorePw, Texts.tr("Password", "Password")) { restorePw = it } },
            confirmButton = {
                TextButton(enabled = restorePw.isNotEmpty(), onClick = {
                    val chars = restorePw.toCharArray()
                    restoreUri = null; restorePw = ""
                    scope.launch {
                        busy = Texts.tr("Checking the backup…", "Verifica del backup…"); error = null; message = null
                        val r = withContext(Dispatchers.IO) {
                            runCatching { ctx.contentResolver.openInputStream(uri)?.use { Backup.stage(ctx, it, chars) } ?: throw java.io.IOException("cannot open the file") }
                        }
                        chars.fill(' ')
                        busy = null
                        r.onSuccess { staged = it }.onFailure { e ->
                            error = if (e is Sealed.BadBackup) Texts.tr("Cannot open: ", "Impossibile aprire: ") + e.message else (e.message ?: e.javaClass.simpleName)
                        }
                    }
                }) { Text(Texts.tr("Open", "Apri")) }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text(Texts.tr("Cancel", "Annulla")) } },
        )
    }

    staged?.let { s ->
        AlertDialog(
            onDismissRequest = { Backup.discardStaged(ctx); staged = null },
            title = { Text(Texts.tr("Replace all data?", "Sostituire tutti i dati?")) },
            text = {
                Text(
                    Texts.tr("Backup of ", "Backup del ") + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.createdMs)) +
                        " (app ${s.appVersion}) · " + Texts.tr("database ", "database ") + "${s.dbBytes / (1 shl 20)} MB · " +
                        "${s.sessions} " + Texts.tr("recordings", "registrazioni") + " · ${s.maps} " + Texts.tr("maps", "mappe") + " · ${s.settings} " + Texts.tr("settings", "impostazioni") + ".\n\n" +
                        Texts.tr(
                            "Everything currently on this phone (database, settings, recordings) is replaced. Collection stops and the app restarts to apply it.",
                            "Tutto ciò che c'è ora su questo telefono (database, impostazioni, registrazioni) viene sostituito. La raccolta si ferma e l'app si riavvia per applicarlo.",
                        ),
                )
            },
            confirmButton = {
                TextButton(onClick = { Backup.commit(ctx); staged = null; restartApp(ctx) }) { Text(Texts.tr("Replace and restart", "Sostituisci e riavvia")) }
            },
            dismissButton = { TextButton(onClick = { Backup.discardStaged(ctx); staged = null }) { Text(Texts.tr("Cancel", "Annulla")) } },
        )
    }
}
