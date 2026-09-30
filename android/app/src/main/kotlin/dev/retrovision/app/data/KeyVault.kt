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
    private const val PREF = "vault"
    private const val PREF_KEY = "p"

    /** @return passphrase as ASCII hex bytes (no NULs), or null if the stored one cannot be decrypted. */
    fun passphrase(ctx: Context, createIfMissing: Boolean = true): ByteArray? {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val key = wrappingKey() ?: return null
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

    /** Forget the stored passphrase (the database becomes unreadable and must be deleted). */
    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun wrappingKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }
        ks.getKey(ALIAS, null) as SecretKey
    } catch (_: Exception) {
        null
    }

    private fun hex(b: ByteArray): ByteArray =
        b.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
}
