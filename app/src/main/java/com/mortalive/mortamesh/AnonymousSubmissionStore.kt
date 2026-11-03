package com.mortalive.mortamesh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App-private encrypted staging queue for anonymous submissions.
 *
 * Staged files are encrypted at rest with an Android Keystore AES-GCM key. This queue is
 * intentionally not the Private Vault and is not the relay queue: it holds the user's own
 * unsent anonymous submission until a configured ingestion package can be created.
 */
internal class AnonymousSubmissionStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "anonymous_submissions").apply { mkdirs() }

    @Synchronized
    fun saveDraft(
        groupId: String,
        text: String,
        mimeType: String?,
        attachment: File?
    ): AnonymousSubmissionDraft {
        val attachmentBytes = attachment?.length() ?: 0L
        val normalizedMime = mimeType?.let(AnonymousSubmissionPolicy::normalizeMime)
        val validation = AnonymousSubmissionPolicy.validate(groupId.trim(), text, normalizedMime, attachmentBytes)
        require(validation == null) { validation ?: "Invalid anonymous submission." }
        if (attachment != null) require(attachment.isFile && attachment.canRead()) { "The selected attachment is not readable." }

        pruneExpired()
        val existing = listDrafts()
        require(existing.size < AnonymousSubmissionPolicy.MAX_QUEUE_ITEMS) { "The anonymous draft queue is full. Remove a draft before adding another." }
        val queuedBytes = existing.sumOf { it.attachmentBytes }
        require(queuedBytes + attachmentBytes <= AnonymousSubmissionPolicy.MAX_QUEUE_BYTES) {
            "The anonymous draft queue has reached its 250 MiB storage limit."
        }

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val draft = AnonymousSubmissionDraft(
            id = id,
            groupId = groupId.trim(),
            text = text,
            mimeType = normalizedMime,
            attachmentBytes = attachmentBytes,
            createdAt = now,
            expiresAt = now + AnonymousSubmissionPolicy.RETENTION_MS
        )
        val payloadFile = payloadFile(id)
        val metadataFile = metadataFile(id)
        try {
            if (attachment != null) {
                encryptToFile(payloadFile, id.toByteArray(Charsets.UTF_8)) { cipherOutput ->
                    FileInputStream(attachment).use { input -> input.copyTo(cipherOutput, 32 * 1024) }
                }
            }
            val metadata = JSONObject()
                .put("v", 1)
                .put("id", draft.id)
                .put("group", draft.groupId)
                .put("text", draft.text)
                .put("mime", draft.mimeType)
                .put("bytes", draft.attachmentBytes)
                .put("created", draft.createdAt)
                .put("expires", draft.expiresAt)
            val metadataBytes = metadata.toString().toByteArray(Charsets.UTF_8)
            encryptToFile(metadataFile, (id + "|metadata").toByteArray(Charsets.UTF_8)) { output ->
                output.write(metadataBytes)
            }
            draft
        } catch (error: Exception) {
            payloadFile.delete()
            metadataFile.delete()
            throw IllegalStateException("The anonymous submission could not be saved securely.", error)
        }
    }

    @Synchronized
    fun listDrafts(): List<AnonymousSubmissionDraft> {
        if (!directory.exists()) return emptyList()
        val result = mutableListOf<AnonymousSubmissionDraft>()
        directory.listFiles { file -> file.isFile && file.name.endsWith(".meta") }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { file ->
                val id = file.name.removeSuffix(".meta")
                val metadata = runCatching {
                    JSONObject(decryptFile(file, (id + "|metadata").toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8))
                }.getOrNull()
                val draft = metadata?.let(::parseDraft)
                if (draft == null || draft.expiresAt <= System.currentTimeMillis()) {
                    deleteDraftFiles(id)
                } else if (draft.hasAttachment && !payloadFile(id).isFile) {
                    deleteDraftFiles(id)
                } else {
                    result += draft
                }
            }
        return result
    }

    @Synchronized
    fun deleteDraft(id: String): Boolean {
        if (!isCanonicalUuid(id)) return false
        val meta = metadataFile(id)
        val payload = payloadFile(id)
        val removed = (!meta.exists() || meta.delete()) && (!payload.exists() || payload.delete())
        return removed
    }

    /**
     * Creates a short-lived plaintext copy for the future ingestion packager. The caller MUST
     * delete the returned file in a finally block immediately after packaging/upload preparation.
     */
    @Synchronized
    fun decryptAttachmentToTemporaryFile(id: String): File? {
        if (!isCanonicalUuid(id)) return null
        val source = payloadFile(id)
        if (!source.isFile) return null
        val output = File(appContext.cacheDir, "anonymous-package-$id.tmp")
        return try {
            decryptFileTo(source, id.toByteArray(Charsets.UTF_8), output)
            output
        } catch (_: Exception) {
            output.delete()
            null
        }
    }

    @Synchronized
    fun pruneExpired() {
        val now = System.currentTimeMillis()
        directory.listFiles()?.forEach { file ->
            if (file.isFile && now - file.lastModified() > AnonymousSubmissionPolicy.RETENTION_MS * 2) {
                // Metadata itself is authenticated/encrypted. Expiry is also checked after decrypt.
                if (file.name.endsWith(".tmp")) file.delete()
            }
        }
        // listDrafts authenticates and removes expired metadata/payload pairs.
        listDrafts()
    }

    private fun parseDraft(value: JSONObject): AnonymousSubmissionDraft? = try {
        if (value.optInt("v") != 1) return null
        val id = value.getString("id")
        val group = value.getString("group")
        val text = value.optString("text")
        val mime = value.optString("mime").takeIf { it.isNotBlank() }
        val bytes = value.optLong("bytes", -1L)
        val created = value.getLong("created")
        val expires = value.getLong("expires")
        if (!isCanonicalUuid(id) || !AnonymousSubmissionPolicy.isValidGroupId(group) ||
            !AnonymousSubmissionPolicy.isValidText(text) || bytes !in 0L..AnonymousSubmissionPolicy.MAX_ATTACHMENT_BYTES ||
            (bytes > 0L && !AnonymousSubmissionPolicy.isAcceptedMime(mime)) ||
            (bytes == 0L && !mime.isNullOrBlank()) || expires <= created) null
        else AnonymousSubmissionDraft(id, group, text, mime, bytes, created, expires)
    } catch (_: Exception) { null }

    private fun encryptToFile(target: File, aad: ByteArray, writePlaintext: (java.io.OutputStream) -> Unit) {
        val temporary = File(target.parentFile, target.name + ".tmp")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad)
        try {
            FileOutputStream(temporary).use { raw ->
                raw.write(FILE_MAGIC)
                raw.write(cipher.iv)
                CipherOutputStream(BufferedOutputStream(raw), cipher).use { encrypted ->
                    writePlaintext(encrypted)
                    encrypted.flush()
                }
            }
            Os.rename(temporary.absolutePath, target.absolutePath)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun decryptFile(file: File, aad: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        decryptFileToStream(file, aad, output)
        return output.toByteArray()
    }

    private fun decryptFileTo(file: File, aad: ByteArray, output: File) {
        FileOutputStream(output).use { target -> decryptFileToStream(file, aad, target) }
    }

    private fun decryptFileToStream(file: File, aad: ByteArray, target: java.io.OutputStream) {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val magic = ByteArray(FILE_MAGIC.size)
            input.readFully(magic)
            check(magic.contentEquals(FILE_MAGIC)) { "Invalid encrypted submission header" }
            val iv = ByteArray(12)
            input.readFully(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            CipherInputStream(input, cipher).use { decrypted -> decrypted.copyTo(target, 32 * 1024) }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
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

    private fun metadataFile(id: String) = File(directory, "$id.meta")
    private fun payloadFile(id: String) = File(directory, "$id.payload")

    private fun deleteDraftFiles(id: String) {
        metadataFile(id).delete()
        payloadFile(id).delete()
    }

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: Exception) { false }

    companion object {
        private const val KEY_ALIAS = "morta_mesh_anonymous_submission_aes_v1"
        private val FILE_MAGIC = byteArrayOf(0x4D, 0x4D, 0x41, 0x4E, 0x31) // MMAN1
    }
}
