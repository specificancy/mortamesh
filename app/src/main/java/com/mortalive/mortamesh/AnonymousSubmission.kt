package com.mortalive.mortamesh

import java.util.Locale

/**
 * Product-level limits for anonymous group submissions. These limits are deliberately
 * separate from private-chat media policy and public World Chat policy.
 */
internal object AnonymousSubmissionPolicy {
    const val MAX_TEXT_CODE_POINTS = 5_000
    const val MAX_ATTACHMENT_BYTES = 100L * 1024L * 1024L
    const val MAX_QUEUE_BYTES = 250L * 1024L * 1024L
    const val MAX_QUEUE_ITEMS = 20
    const val RETENTION_MS = 72L * 60L * 60L * 1_000L
    const val MAX_RELAY_HOPS = 8
    const val RELAY_CHUNK_BYTES = 96 * 1024

    private val acceptedMimeTypes = setOf(
        "image/jpeg", "image/png", "image/webp",
        "video/mp4", "video/3gpp",
        "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg",
        "audio/3gpp", "audio/wav", "audio/x-wav"
    )

    fun normalizeMime(mime: String?): String = mime.orEmpty().trim().lowercase(Locale.ROOT)

    fun isAcceptedMime(mime: String?): Boolean = normalizeMime(mime) in acceptedMimeTypes

    fun isValidGroupId(groupId: String): Boolean =
        groupId.length in 6..128 && GROUP_ID.matches(groupId)

    fun isValidText(text: String): Boolean {
        if (text.codePointCount(0, text.length) > MAX_TEXT_CODE_POINTS) return false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                Character.isHighSurrogate(ch) -> {
                    if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(ch) -> return false
                ch.isISOControl() && ch != '\n' && ch != '\r' && ch != '\t' -> return false
                else -> index++
            }
        }
        return true
    }

    fun validate(groupId: String, text: String, mime: String?, attachmentBytes: Long): String? {
        if (!isValidGroupId(groupId)) return "Enter a valid Mortalive group ID or submission code."
        if (!isValidText(text)) return "Text is too long or contains unsupported control characters."
        if (text.isBlank() && attachmentBytes <= 0L) return "Add text or choose a photo, video, or audio file."
        if (attachmentBytes < 0L || attachmentBytes > MAX_ATTACHMENT_BYTES) return "The attachment must be 100 MiB or smaller."
        if (attachmentBytes > 0L && !isAcceptedMime(mime)) return "This photo, video, or audio format is not supported."
        if (attachmentBytes == 0L && !mime.isNullOrBlank()) return "An attachment type was supplied without an attachment."
        return null
    }

    private val GROUP_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{4,126}[A-Za-z0-9]$")
}

internal data class AnonymousSubmissionDraft(
    val id: String,
    val groupId: String,
    val text: String,
    val mimeType: String?,
    val attachmentBytes: Long,
    val createdAt: Long,
    val expiresAt: Long
) {
    val hasAttachment: Boolean get() = attachmentBytes > 0L
    val statusLabel: String get() = "Encrypted local draft · awaiting Mortalive integration"
}
