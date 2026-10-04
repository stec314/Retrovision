// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 stec314 and the Retrovision contributors
package dev.retrovision.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the database passphrase. The passphrase is random, stored only encrypted, and the
 * key that encrypts it lives in the Android Keystore (not exportable).
 */
object KeyVault {
    private const val ALIAS = "retrovision-db-wrap"
    private const val SECRET_ALIAS = "retrovision-settings"
    private const val PREF = "vault"
    private const val PREF_KEY = "p"

    /** @return passphrase as ASCII hex bytes (no NULs), or null if the stored one cannot be decrypted. */
    fun passphrase(ctx: Context, createIfMissing: Boolean = true): ByteArray? {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val key = wrappingKey(ALIAS) ?: return null
        val stored = prefs.getString(PREF_KEY, null)
        if (stored != null) {
            return try {
                val raw = Base64.decode(stored, Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
                hex(c.doFinal(raw, 12, raw.size - 12))
            } catch (_: Exception) {
                null
            }
        }
        if (!createIfMissing) return null
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val enc = c.doFinal(pass)
        prefs.edit().putString(PREF_KEY, Base64.encodeToString(c.iv + enc, Base64.NO_WRAP)).apply()
        return hex(pass)
    }

    /**
     * Replaces the stored passphrase with one restored from a backup ([hexPass] as returned by
     * [passphrase]). Only for the restore step at start-up, before the database is opened.
     */
    fun importPassphrase(ctx: Context, hexPass: ByteArray): Boolean {
        val txt = String(hexPass, Charsets.US_ASCII).trim()
        if (txt.length != 64 || !txt.all { it in "0123456789abcdef" }) return false
        val raw = ByteArray(32) { i -> txt.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        val key = wrappingKey(ALIAS) ?: return false
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            val enc = c.doFinal(raw)
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(PREF_KEY, Base64.encodeToString(c.iv + enc, Base64.NO_WRAP)).commit()
        } catch (_: Exception) {
            false
        } finally {
            raw.fill(0)
        }
    }

    /** Forget the stored passphrase (the database becomes unreadable and must be deleted). */
    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    /**
     * Key for session recordings, derived from the database passphrase: same protection as the
     * database, and wiping the vault makes old recordings unreadable too.
     */
    fun sessionKey(ctx: Context): ByteArray? {
        val pass = passphrase(ctx, createIfMissing = false) ?: return null
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest("retrovision-sessions-v1".toByteArray() + pass)
    }

    /** Encrypts a small setting with a Keystore key. Null if the Keystore is unavailable. */
    fun seal(plain: String): String? {
        val key = wrappingKey(SECRET_ALIAS) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun open(sealed: String): String? {
        val key = wrappingKey(SECRET_ALIAS) ?: return null
        return try {
            val raw = Base64.decode(sealed, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun wrappingKey(alias: String): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }
        ks.getKey(alias, null) as SecretKey
    } catch (_: Exception) {
        null
    }

    private fun hex(b: ByteArray): ByteArray =
        b.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
}
