package com.mortalive.mortamesh

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Bridges an encrypted local draft to the sender-to-Mortalive encrypted package format.
 *
 * This helper deliberately requires the pinned ingestion public key and key ID to be supplied
 * by the future Mortalive integration. It never guesses a server URL/key and never sends content.
 */
internal class AnonymousSubmissionPackager(
    context: Context,
    private val drafts: AnonymousSubmissionStore
) {
    private val cache = File(context.applicationContext.cacheDir, "anonymous-sealed-packages").apply { mkdirs() }

    fun prepare(
        draft: AnonymousSubmissionDraft,
        ingestionPublicKeyX509Base64: String,
        ingestionKeyId: String
    ): AnonymousSubmissionCrypto.EncryptedPackage {
        val plaintextAttachment = if (draft.hasAttachment) {
            drafts.decryptAttachmentToTemporaryFile(draft.id)
                ?: throw IllegalStateException("The encrypted draft attachment could not be opened.")
        } else null

        val output = File(cache, "${draft.id}-${UUID.randomUUID()}.mmp")
        return try {
            AnonymousSubmissionCrypto.sealForIngestion(
                groupId = draft.groupId,
                text = draft.text,
                mimeType = draft.mimeType,
                attachment = plaintextAttachment,
                ingestionPublicKeyX509Base64 = ingestionPublicKeyX509Base64,
                ingestionKeyId = ingestionKeyId,
                outputFile = output
            )
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            // Plaintext exists only during package construction. Always remove it immediately.
            plaintextAttachment?.delete()
        }
    }

    fun stageForRelay(
        draftId: String,
        ingestionPublicKeyX509Base64: String,
        ingestionKeyId: String,
        relayStore: AnonymousRelayStore
    ): Boolean {
        val draft = drafts.listDrafts().firstOrNull { it.id == draftId } ?: return false
        val packageValue = try {
            prepare(draft, ingestionPublicKeyX509Base64, ingestionKeyId)
        } catch (_: Exception) {
            return false
        }
        return try {
            relayStore.enqueueLocalPackage(packageValue)
        } finally {
            deletePackage(packageValue)
        }
    }

    fun deletePackage(packageValue: AnonymousSubmissionCrypto.EncryptedPackage) {
        packageValue.ciphertextFile.delete()
    }
}
