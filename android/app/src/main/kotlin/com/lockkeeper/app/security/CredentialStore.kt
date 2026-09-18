package com.lockkeeper.app.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

interface CredentialStore {
    fun setPin(pin: String): Boolean
    fun verifyPin(pin: String): Boolean
    fun hasPin(): Boolean
    fun getPinLength(): Int

    fun setAdminPassword(password: String): Boolean
    fun verifyAdminPassword(password: String): Boolean
    fun hasAdminPassword(): Boolean

    fun clear()
}

class KeystoreCredentialStore(
    private val prefs: SharedPreferences
) : CredentialStore {

    constructor(context: Context, prefsName: String = "lockkeeper_credentials") :
        this(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE))


    private val secureRandom = SecureRandom()

    companion object {
        private const val KEY_ALIAS = "LockKeeperMasterKey_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12

        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val KDF_ITERATIONS = 65536
        private const val KDF_KEY_LENGTH = 256
        private const val SALT_BYTES = 16

        private const val PREF_KEY_PIN = "cred_pin_blob"
        private const val PREF_KEY_PIN_LEN = "cred_pin_len"
        private const val PREF_KEY_ADMIN = "cred_admin_blob"
    }

    private fun getOrCreateMasterKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
            (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } catch (e: Exception) {
            val isAndroid = try {
                System.getProperty("java.runtime.name")?.contains("Android", ignoreCase = true) == true
            } catch (_: Exception) {
                false
            }
            if (isAndroid) {
                throw SecurityException("AndroidKeyStore provider is required in Android runtime", e)
            }
            // JVM Unit Test Fallback (when AndroidKeyStore provider is absent on JVM)
            getJvmFallbackKey()
        }
    }

    private var jvmKeyCache: SecretKey? = null
    private fun getJvmFallbackKey(): SecretKey {
        if (jvmKeyCache == null) {
            val kgen = KeyGenerator.getInstance("AES")
            kgen.init(256, secureRandom)
            jvmKeyCache = kgen.generateKey()
        }
        return jvmKeyCache!!
    }

    private fun encrypt(plaintext: String): String {
        val key = getOrCreateMasterKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv ?: ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        
        // Output format: Base64(iv + ciphertext)
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    private fun decrypt(encryptedBase64: String): String? {
        return try {
            val combined = Base64.getDecoder().decode(encryptedBase64)
            if (combined.size < GCM_IV_LENGTH) return null
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val key = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun hashCredential(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, KDF_ITERATIONS, KDF_KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    private fun formatStoragePayload(salt: ByteArray, hash: ByteArray): String {
        val saltB64 = Base64.getEncoder().encodeToString(salt)
        val hashB64 = Base64.getEncoder().encodeToString(hash)
        return "v1:$KDF_ITERATIONS:$saltB64:$hashB64"
    }

    private fun verify(candidate: String, storedEncrypted: String?): Boolean {
        if (storedEncrypted == null) return false
        val decrypted = decrypt(storedEncrypted) ?: return false
        val parts = decrypted.split(":")
        if (parts.size != 4) return false
        val salt = Base64.getDecoder().decode(parts[2])
        val storedHash = Base64.getDecoder().decode(parts[3])

        val candidateHash = hashCredential(candidate, salt)
        return MessageDigest.isEqual(storedHash, candidateHash)
    }

    override fun setPin(pin: String): Boolean {
        if (pin.length < 4 || pin.length > 8 || !pin.all { it.isDigit() }) {
            return false
        }
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val hash = hashCredential(pin, salt)
        val payload = formatStoragePayload(salt, hash)
        val encrypted = encrypt(payload)
        prefs.edit()
            .putString(PREF_KEY_PIN, encrypted)
            .putInt(PREF_KEY_PIN_LEN, pin.length)
            .apply()
        return true
    }

    override fun verifyPin(pin: String): Boolean {
        val stored = prefs.getString(PREF_KEY_PIN, null) ?: return false
        return verify(pin, stored)
    }

    override fun hasPin(): Boolean {
        return prefs.contains(PREF_KEY_PIN)
    }

    override fun getPinLength(): Int {
        return prefs.getInt(PREF_KEY_PIN_LEN, 4)
    }

    override fun setAdminPassword(password: String): Boolean {
        if (password.length < 4) return false
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val hash = hashCredential(password, salt)
        val payload = formatStoragePayload(salt, hash)
        val encrypted = encrypt(payload)
        prefs.edit().putString(PREF_KEY_ADMIN, encrypted).apply()
        return true
    }

    override fun verifyAdminPassword(password: String): Boolean {
        val stored = prefs.getString(PREF_KEY_ADMIN, null) ?: return false
        return verify(password, stored)
    }

    override fun hasAdminPassword(): Boolean {
        return prefs.contains(PREF_KEY_ADMIN)
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}
