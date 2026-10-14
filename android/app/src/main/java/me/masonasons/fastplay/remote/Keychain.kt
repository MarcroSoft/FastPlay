package me.masonasons.fastplay.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import me.masonasons.fastplay.FastPlayApp
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The few secrets FastPlay keeps (servers' passwords, the Dropbox sign in), as the
 * iPhone's keychain keeps them: one secret under one name. Each is encrypted
 * (AES-GCM) with a key that lives in the Android Keystore and never leaves it, and
 * the encrypted text is kept in a preferences file of its own. A secret that
 * cannot be read back (the app's data restored onto another device, which does not
 * have the key) is as good as none, and is forgotten.
 */
object Keychain {
    private const val KEY_ALIAS = "FastPlaySecrets"
    private const val FILE = "FastPlaySecrets"
    private const val IV_LENGTH = 12

    /** What has been read or written this run, so the Keystore is asked once a secret. */
    private val cache = mutableMapOf<String, String?>()
    private var key: SecretKey? = null

    private val prefs get() = FastPlayApp.instance.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun read(name: String): String? {
        if (cache.containsKey(name)) return cache[name]
        val stored = prefs.getString(name, null)
        val value = stored?.let {
            try {
                val bytes = Base64.decode(it, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
                String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
            } catch (e: Exception) {
                // Not readable here: forgotten, as if it had never been kept
                prefs.edit().remove(name).apply()
                null
            }
        }
        cache[name] = value
        return value
    }

    @Synchronized
    fun write(name: String, value: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val sealed = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(name, Base64.encodeToString(iv + sealed, Base64.NO_WRAP)).apply()
            cache[name] = value
        } catch (e: Exception) {
            // The Keystore would not have it: kept for this run only, never in the clear
            prefs.edit().remove(name).apply()
            cache[name] = value
        }
    }

    @Synchronized
    fun delete(name: String) {
        prefs.edit().remove(name).apply()
        cache[name] = null
    }

    /** The Keystore's key for FastPlay's secrets, made the first time. */
    private fun key(): SecretKey {
        key?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val found = store.getKey(KEY_ALIAS, null) as? SecretKey ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        }
        key = found
        return found
    }
}
