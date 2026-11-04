package com.mortalive.mortamesh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encrypted recovery material in no-backup app-private storage.
 * The non-exportable wrapping key is held by Android Keystore.
 */
internal class WalletSecretVault(context: Context) {
    private val root = File(context.noBackupFilesDir, "wallet-secrets")

    fun exists(walletId: String, networkId: String): Boolean =
        secretFile(walletId, networkId).isFile

    fun save(walletId: String, networkId: String, secret: ByteArray) =
        synchronized(SAVE_LOCK) { saveLocked(walletId, networkId, secret) }

    private fun saveLocked(walletId: String, networkId: String, secret: ByteArray) {
        require(secret.isNotEmpty()) { "Wallet secret cannot be empty" }
        val target = secretFile(walletId, networkId)
        check(!target.exists()) { "Wallet recovery data already exists; refusing to overwrite it" }
        check(root.exists() || root.mkdirs()) { "Could not create private wallet storage" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(walletId, networkId))
        cipher.updateAAD(WalletSecretEnvelopeCodec.aad(walletId, networkId))
        val encrypted = cipher.doFinal(secret)
        val envelope = WalletSecretEnvelopeCodec.encode(cipher.iv, encrypted)
        val temporary = File(root, target.name + ".tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(envelope)
                output.fd.sync()
            }
            try {
                // link() fails with EEXIST instead of replacing an existing recovery file.
                Os.link(temporary.absolutePath, target.absolutePath)
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EEXIST) {
                    throw IllegalStateException("Wallet recovery data already exists; refusing to overwrite it", error)
                }
                // The process-wide lock and this re-check protect the fallback on filesystems that reject hard links.
                check(!target.exists()) { "Wallet recovery data already exists; refusing to overwrite it" }
                check(temporary.renameTo(target)) { "Could not atomically store encrypted wallet recovery data" }
            }
            syncRootDirectory()
        } finally {
            temporary.delete()
            envelope.fill(0)
            encrypted.fill(0)
        }
    }

    fun load(walletId: String, networkId: String): ByteArray? {
        val file = secretFile(walletId, networkId)
        if (!file.exists()) return null

        val envelopeBytes = file.readBytes()
        try {
            val payload = WalletSecretEnvelopeCodec.decode(envelopeBytes)
            val keyStore = loadKeyStore()
            val key = keyStore.getKey(alias(walletId, networkId), null) as? SecretKey
                ?: throw IllegalStateException("Wallet encryption key is missing; encrypted wallet data was preserved")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, payload.iv))
            cipher.updateAAD(WalletSecretEnvelopeCodec.aad(walletId, networkId))
            return cipher.doFinal(payload.ciphertext)
        } finally {
            envelopeBytes.fill(0)
        }
    }

    /** Best-effort durability for the newly linked directory entry. */
    private fun syncRootDirectory() {
        try {
            val fd = Os.open(root.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        } catch (_: Exception) {
            // The encrypted file itself was already fsynced; directory fsync is best-effort on Android filesystems.
        }
    }

    private fun secretFile(walletId: String, networkId: String): File {
        validateScope(walletId, networkId)
        return File(root, networkId + "_" + walletId + ".mmw")
    }

    private fun getOrCreateKey(walletId: String, networkId: String): SecretKey {
        val keyStore = loadKeyStore()
        val keyAlias = alias(walletId, networkId)
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun alias(walletId: String, networkId: String): String {
        val material = ("MortaMesh|wallet-key|v1|" + networkId + "|" + walletId).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(material)
        return "morta_wallet_" + digest.take(20).joinToString("") { "%02x".format(it) }
    }

    private fun validateScope(walletId: String, networkId: String) {
        require(walletId.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Invalid wallet identifier" }
        require(networkId.matches(Regex("[a-zA-Z0-9_-]{1,32}"))) { "Invalid wallet network identifier" }
    }

    companion object {
        private val SAVE_LOCK = Any()
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

internal data class WalletSecretPayload(val iv: ByteArray, val ciphertext: ByteArray)

/** Versioned envelope codec kept pure so its framing can be unit-tested without Android Keystore. */
internal object WalletSecretEnvelopeCodec {
    private val MAGIC = byteArrayOf(0x4D, 0x4D, 0x57, 0x53) // MMWS
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val HEADER_BYTES = 6

    fun encode(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == IV_BYTES) { "AES-GCM IV must be 12 bytes" }
        require(ciphertext.size >= TAG_BYTES) { "AES-GCM ciphertext is missing its authentication tag" }
        return ByteArray(HEADER_BYTES + iv.size + ciphertext.size).also { result ->
            MAGIC.copyInto(result, 0)
            result[4] = VERSION
            result[5] = IV_BYTES.toByte()
            iv.copyInto(result, HEADER_BYTES)
            ciphertext.copyInto(result, HEADER_BYTES + iv.size)
        }
    }

    fun decode(bytes: ByteArray): WalletSecretPayload {
        require(bytes.size >= HEADER_BYTES + IV_BYTES + TAG_BYTES) { "Encrypted wallet file is truncated" }
        require(MAGIC.indices.all { bytes[it] == MAGIC[it] }) { "Unknown encrypted wallet file format" }
        require(bytes[4] == VERSION) { "Unsupported encrypted wallet file version" }
        require(bytes[5].toInt() == IV_BYTES) { "Invalid encrypted wallet IV length" }
        return WalletSecretPayload(
            iv = bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + IV_BYTES),
            ciphertext = bytes.copyOfRange(HEADER_BYTES + IV_BYTES, bytes.size)
        )
    }

    fun aad(walletId: String, networkId: String): ByteArray {
        require(walletId.isNotBlank() && networkId.isNotBlank())
        return ("MortaMesh|wallet-secret|v1|" + networkId + "|" + walletId).toByteArray(Charsets.UTF_8)
    }
}
