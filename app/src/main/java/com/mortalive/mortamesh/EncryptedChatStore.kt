package com.mortalive.mortamesh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts local conversation history at rest with an Android Keystore-protected AES-GCM key. */
internal class EncryptedChatStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val tempFile = File(appContext.filesDir, "$FILE_NAME.tmp")

    @Synchronized
    fun readText(): String? {
        if (!file.exists()) return null
        val bytes = try { file.readBytes() } catch (e: Exception) {
            throw IllegalStateException("Could not read encrypted conversation history", e)
        }
        if (bytes.size < HEADER.size + IV_BYTES + TAG_BYTES || bytes.size > MAX_FILE_BYTES) {
            throw IllegalStateException("Encrypted conversation history has an invalid size")
        }
        if (!bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            throw IllegalStateException("Encrypted conversation history has an unknown format")
        }
        return try {
            val ivStart = HEADER.size
            val iv = bytes.copyOfRange(ivStart, ivStart + IV_BYTES)
            val ciphertext = bytes.copyOfRange(ivStart + IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getExistingKeyOrThrow(), GCMParameterSpec(128, iv))
            cipher.updateAAD(HEADER)
            val plaintext = cipher.doFinal(ciphertext)
            if (plaintext.size > MAX_PLAINTEXT_BYTES) throw IllegalStateException("Conversation history is too large")
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("Could not decrypt conversation history; the original encrypted file has been preserved", e)
        }
    }

    /** Returns false if the serialized history is too large or cannot be committed safely. */
    @Synchronized
    fun writeText(text: String): Boolean {
        val plaintext = text.toByteArray(Charsets.UTF_8)
        if (plaintext.size > MAX_PLAINTEXT_BYTES) return false
        return try {
            // Do not mint a replacement key over an existing history file: that would destroy
            // data that may still be recoverable if the original Keystore key returns.
            if (file.exists()) getExistingKeyOrThrow()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Keystore-backed AES keys generate their own IV when randomized encryption is required.
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv ?: return false
            if (iv.size != IV_BYTES) return false
            cipher.updateAAD(HEADER)
            val ciphertext = cipher.doFinal(plaintext)
            FileOutputStream(tempFile).use { output ->
                output.write(HEADER)
                output.write(iv)
                output.write(ciphertext)
                output.fd.sync()
            }
            // Atomic same-filesystem replacement: the previous encrypted history remains intact
            // if encryption or writing fails before the rename.
            Os.rename(tempFile.absolutePath, file.absolutePath)
            true
        } catch (_: Exception) {
            try { tempFile.delete() } catch (_: Exception) { }
            false
        }
    }

    private fun getExistingKeyOrThrow(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Android Keystore history key is missing")
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val FILE_NAME = "morta_mesh_chats.enc"
        private const val KEY_ALIAS = "morta_mesh_chat_history_key_v1"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
        private const val MAX_FILE_BYTES = MAX_PLAINTEXT_BYTES + 128
        private val HEADER = byteArrayOf(0x4D, 0x4D, 0x43, 0x48, 0x31) // MMCH1
    }
}
