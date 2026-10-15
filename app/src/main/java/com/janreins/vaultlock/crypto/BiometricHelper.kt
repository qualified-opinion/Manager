package com.janreins.vaultlock.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Biometric authentication & Android Keystore integration.
 * Securely wraps the derived Master Key inside hardware-backed Android Keystore
 * with setUserAuthenticationRequired(true) and BIOMETRIC_STRONG only.
 * The Master Key is unwrapped exclusively via a hardware-authenticated CryptoObject.
 */
object BiometricHelper {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "VaultLockBiometricWrapperKey"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val AES_GCM_CIPHER = "AES/GCM/NoPadding"

    fun isBiometricAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun buildKeyGenParameterSpec(alias: String = KEYSTORE_ALIAS): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setInvalidatedByBiometricEnrollment(true)
        }
        return builder.build()
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            keyGenerator.init(buildKeyGenParameterSpec(KEYSTORE_ALIAS))
            keyGenerator.generateKey()
        }
        return (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun getKeystoreKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                null
            } else {
                val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
                entry?.secretKey
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isKeyPermanentlyInvalidated(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is android.security.keystore.KeyPermanentlyInvalidatedException) {
                return true
            }
            val msg = cause.message?.lowercase() ?: ""
            if (msg.contains("key permanently invalidated") || msg.contains("key invalidated")) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    fun deleteKeystoreKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                keyStore.deleteEntry(KEYSTORE_ALIAS)
            }
        } catch (_: Exception) {
            // Ignore keystore deletion failure
        }
    }

    /**
     * Enrolls biometric authentication by encrypting the master key using a Keystore key
     * authenticated via BiometricPrompt.CryptoObject.
     */
    fun promptBiometricEnrollment(
        activity: FragmentActivity,
        masterKeyToWrap: SecretKey,
        title: String = "Enable Biometric Unlock",
        subtitle: String = "Authenticate to securely register biometric unlock",
        negativeButtonText: String = "Cancel",
        onEnrolled: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            // Delete previous key if any to ensure fresh generation
            deleteKeystoreKey()
            val keystoreKey = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance(AES_GCM_CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey)
            val cryptoObject = BiometricPrompt.CryptoObject(cipher)

            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        val authenticatedCipher = result.cryptoObject?.cipher
                        if (authenticatedCipher == null) {
                            onError("Biometric authentication missing authenticated cipher")
                            return
                        }
                        try {
                            val iv = authenticatedCipher.iv
                            val encryptedKeyBytes = authenticatedCipher.doFinal(masterKeyToWrap.encoded)
                            val combined = ByteArray(iv.size + encryptedKeyBytes.size)
                            System.arraycopy(iv, 0, combined, 0, iv.size)
                            System.arraycopy(encryptedKeyBytes, 0, combined, iv.size, encryptedKeyBytes.size)
                            val wrappedBase64 = Base64.encodeToString(combined, Base64.NO_WRAP)
                            onEnrolled(wrappedBase64)
                        } catch (e: Exception) {
                            onError("Failed to wrap master key: ${e.localizedMessage}")
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            onError("cancelled")
                        } else {
                            onError(errString.toString())
                        }
                    }

                    // onAuthenticationFailed (one unrecognised finger or face) is not terminal: the prompt
                    // stays open and shows its own retry hint, and onAuthenticationError reports the end.
                }
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(negativeButtonText)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()

            prompt.authenticate(promptInfo, cryptoObject)
        } catch (e: Exception) {
            onError("Biometric initialization failed: ${e.localizedMessage}")
        }
    }

    /**
     * Authenticates user via BiometricPrompt.CryptoObject and decrypts (unwraps) the Master Key
     * in one atomic hardware-authenticated step.
     */
    fun promptBiometricUnlock(
        activity: FragmentActivity,
        wrappedKeyBase64: String,
        title: String = "Unlock VaultLock",
        subtitle: String = "Verify identity with strong biometrics",
        negativeButtonText: String = "Use Master Password",
        onInvalidated: (() -> Unit)? = null,
        onSuccess: (SecretKey) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val combined = Base64.decode(wrappedKeyBase64, Base64.NO_WRAP)
            if (combined.size < GCM_IV_LENGTH) {
                onError("Invalid wrapped key format")
                return
            }

            val iv = ByteArray(GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)

            val cipherTextSize = combined.size - GCM_IV_LENGTH
            val cipherText = ByteArray(cipherTextSize)
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherTextSize)

            val keystoreKey = getKeystoreKey()
            if (keystoreKey == null) {
                deleteKeystoreKey()
                onInvalidated?.invoke()
                onError("Biometric key missing. Please unlock with Master Password and re-enable biometric unlock.")
                return
            }

            val cipher = Cipher.getInstance(AES_GCM_CIPHER)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey, spec)
            val cryptoObject = BiometricPrompt.CryptoObject(cipher)

            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        val authenticatedCipher = result.cryptoObject?.cipher
                        if (authenticatedCipher == null) {
                            onError("Biometric authentication missing authenticated cipher")
                            return
                        }
                        var decryptedKeyBytes: ByteArray? = null
                        try {
                            decryptedKeyBytes = authenticatedCipher.doFinal(cipherText)
                            val secretKey = SecretKeySpec(decryptedKeyBytes, "AES")
                            onSuccess(secretKey)
                        } catch (e: Exception) {
                            if (isKeyPermanentlyInvalidated(e)) {
                                deleteKeystoreKey()
                                onInvalidated?.invoke()
                                onError("Biometric credentials invalidated. Please unlock with Master Password and re-enable biometric unlock.")
                            } else {
                                onError("Failed to unwrap key: ${e.localizedMessage}")
                            }
                        } finally {
                            decryptedKeyBytes?.fill(0)
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            onError("cancelled")
                        } else {
                            onError(errString.toString())
                        }
                    }

                    // onAuthenticationFailed (one unrecognised finger or face) is not terminal: the prompt
                    // stays open and shows its own retry hint, and onAuthenticationError reports the end.
                }
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(negativeButtonText)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()

            prompt.authenticate(promptInfo, cryptoObject)
        } catch (e: Exception) {
            if (isKeyPermanentlyInvalidated(e)) {
                deleteKeystoreKey()
                onInvalidated?.invoke()
                onError("Biometric credentials invalidated. Please unlock with Master Password and re-enable biometric unlock.")
            } else {
                onError("Biometric setup failed: ${e.localizedMessage}")
            }
        }
    }
}
