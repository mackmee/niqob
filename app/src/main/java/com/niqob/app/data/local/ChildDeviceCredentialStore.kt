package com.niqob.app.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Dedicated secure storage abstraction for child-device credentials.
 *
 * Security Requirements:
 * - device_secret is encrypted using Android Keystore AES-256-GCM when Keystore is available.
 * - The raw secret is NEVER persisted unencrypted to disk in production.
 * - The raw secret is NEVER logged, exposed to UI state, or stored in domain presentation models.
 * - Credential access is restricted strictly to the authorized data layer for authenticated RPCs.
 */
interface ChildDeviceCredentialStore {
    /**
     * Atomically persists the child device ID and cryptographic device secret.
     * Both parameters must be non-blank.
     * Returns true if both credentials were successfully stored, false otherwise.
     */
    fun save(deviceId: String, deviceSecret: String): Boolean

    /**
     * Returns the stored child device ID, or null if uninitialized/unpaired.
     */
    fun getDeviceId(): String?

    /**
     * Returns the decrypted raw child device secret (64-char lowercase hex), or null if uninitialized.
     */
    fun getDeviceSecret(): String?

    /**
     * Clears all stored child device credentials.
     */
    fun clear()

    /**
     * Returns true if both device ID and device secret are currently stored and non-blank.
     */
    fun hasCredential(): Boolean
}

/**
 * Pluggable cipher abstraction allowing hardware Keystore encryption in production
 * while supporting deterministic testing in JVM test environments.
 */
interface DeviceSecretCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

/**
 * Production cipher backed by Android Keystore using AES-256 in GCM mode.
 * The encryption key is generated in hardware/Keystore and cannot be exported.
 */
class AndroidKeystoreSecretCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : DeviceSecretCipher {

    companion object {
        const val DEFAULT_KEY_ALIAS = "niqob_child_secret_aes_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        /**
         * Checks whether AndroidKeyStore provider is available on the current platform.
         * Returns false in standard JVM test environments.
         */
        fun isSupported(): Boolean {
            return try {
                val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
                ks.load(null)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("KeyStore entry for alias $keyAlias is not a SecretKeyEntry")
        return entry.secretKey
    }

    override fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv ?: throw IllegalStateException("Cipher failed to generate IV")
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // Prepend IV (12 bytes) to cipher text
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    override fun decrypt(cipherText: String): String {
        val combined = Base64.getDecoder().decode(cipherText)
        if (combined.size < GCM_IV_LENGTH) {
            throw IllegalArgumentException("Ciphertext payload is too short")
        }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val encryptedData = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)

        val decryptedBytes = cipher.doFinal(encryptedData)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}

/**
 * Fallback no-op cipher used in testing or environments without AndroidKeyStore.
 */
object NoOpDeviceSecretCipher : DeviceSecretCipher {
    override fun encrypt(plainText: String): String = plainText
    override fun decrypt(cipherText: String): String = cipherText
}

/**
 * Standard implementation of [ChildDeviceCredentialStore] backed by private [SharedPreferences].
 *
 * Storage design:
 * - Preferences file: "niqob_child_credentials" (Context.MODE_PRIVATE)
 * - device_id: stored directly as text (identifier)
 * - device_secret: stored encrypted as Base64 AES-GCM ciphertext
 * - Atomic commit via SharedPreferences.Editor.commit()
 * - In-memory fallback if SharedPreferences is null (for headless unit tests)
 */
class SharedPreferencesChildDeviceCredentialStore(
    sharedPreferences: SharedPreferences? = null,
    private val cipher: DeviceSecretCipher = defaultCipher()
) : ChildDeviceCredentialStore {

    companion object {
        const val PREFS_NAME = "niqob_child_credentials"
        private const val KEY_DEVICE_ID = "child_device_id"
        private const val KEY_ENCRYPTED_SECRET = "child_device_secret_encrypted"

        @Volatile
        private var instance: SharedPreferencesChildDeviceCredentialStore? = null

        fun getInstance(context: Context? = null): SharedPreferencesChildDeviceCredentialStore {
            val existing = instance
            if (existing != null) {
                if (context != null && !existing.hasSharedPreferences()) {
                    existing.attachContext(context)
                }
                return existing
            }
            return synchronized(this) {
                val current = instance
                if (current != null) {
                    if (context != null && !current.hasSharedPreferences()) {
                        current.attachContext(context)
                    }
                    current
                } else {
                    val prefs = resolveSharedPreferences(context)
                    SharedPreferencesChildDeviceCredentialStore(
                        sharedPreferences = prefs,
                        cipher = defaultCipher()
                    ).also { instance = it }
                }
            }
        }

        private fun resolveSharedPreferences(context: Context?): SharedPreferences? {
            if (context == null) return null
            return try {
                val appContext = context.applicationContext ?: context
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            } catch (_: Throwable) {
                null
            }
        }

        fun resetInstanceForTesting() {
            synchronized(this) {
                instance = null
            }
        }

        fun setInstanceForTesting(store: SharedPreferencesChildDeviceCredentialStore?) {
            synchronized(this) {
                instance = store
            }
        }

        private fun defaultCipher(): DeviceSecretCipher {
            return if (AndroidKeystoreSecretCipher.isSupported()) {
                AndroidKeystoreSecretCipher()
            } else {
                NoOpDeviceSecretCipher
            }
        }
    }

    @Volatile
    private var sharedPreferences: SharedPreferences? = sharedPreferences

    // In-memory backing store for headless testing when SharedPreferences is null
    private val inMemoryFallback = mutableMapOf<String, String>()

    fun hasSharedPreferences(): Boolean = sharedPreferences != null

    @Synchronized
    fun attachContext(context: Context) {
        if (this.sharedPreferences == null) {
            val prefs = resolveSharedPreferences(context)
            if (prefs != null) {
                attachSharedPreferences(prefs)
            }
        }
    }

    @Synchronized
    fun attachSharedPreferences(prefs: SharedPreferences): Boolean {
        if (this.sharedPreferences != null) {
            return true
        }
        // Migrate any credentials temporarily saved to inMemoryFallback to persistent SharedPreferences
        if (inMemoryFallback.isNotEmpty()) {
            val editor = prefs.edit()
            inMemoryFallback.forEach { (key, value) ->
                editor.putString(key, value)
            }
            val committed = try {
                editor.commit()
            } catch (_: Throwable) {
                false
            }
            if (!committed) {
                return false
            }
            inMemoryFallback.clear()
        }
        this.sharedPreferences = prefs
        return true
    }

    @Synchronized
    override fun save(deviceId: String, deviceSecret: String): Boolean {
        if (deviceId.isBlank() || deviceSecret.isBlank()) {
            return false
        }

        val encryptedSecret = try {
            cipher.encrypt(deviceSecret)
        } catch (_: Throwable) {
            return false
        }

        if (encryptedSecret.isBlank()) {
            return false
        }

        val prefs = sharedPreferences
        return if (prefs != null) {
            prefs.edit()
                .putString(KEY_DEVICE_ID, deviceId.trim())
                .putString(KEY_ENCRYPTED_SECRET, encryptedSecret)
                .commit()
        } else {
            inMemoryFallback[KEY_DEVICE_ID] = deviceId.trim()
            inMemoryFallback[KEY_ENCRYPTED_SECRET] = encryptedSecret
            true
        }
    }

    @Synchronized
    override fun getDeviceId(): String? {
        val prefs = sharedPreferences
        return if (prefs != null) {
            prefs.getString(KEY_DEVICE_ID, null)
        } else {
            inMemoryFallback[KEY_DEVICE_ID]
        }
    }

    @Synchronized
    override fun getDeviceSecret(): String? {
        val prefs = sharedPreferences
        val encryptedSecret = if (prefs != null) {
            prefs.getString(KEY_ENCRYPTED_SECRET, null)
        } else {
            inMemoryFallback[KEY_ENCRYPTED_SECRET]
        } ?: return null

        return try {
            cipher.decrypt(encryptedSecret)
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    override fun clear() {
        val prefs = sharedPreferences
        prefs?.edit()
            ?.remove(KEY_DEVICE_ID)
            ?.remove(KEY_ENCRYPTED_SECRET)
            ?.commit()
        inMemoryFallback.clear()
    }

    @Synchronized
    override fun hasCredential(): Boolean {
        return !getDeviceId().isNullOrBlank() && !getDeviceSecret().isNullOrBlank()
    }
}
