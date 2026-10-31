package com.mortalive.mortamesh

/**
 * Media routing is based on media type, never on file size.
 * When the required transport is unavailable, queue the item instead of falling back.
 */
internal object MediaTransferPolicy {
    enum class RequiredTransport { BLUETOOTH_LE, WIFI_DIRECT }

    /** Only the formats this app itself produces are accepted from a sender. */
    private val ACCEPTED_MIME_TYPES = setOf(
        "image/jpeg", "image/png", "image/webp", "video/mp4", "video/3gpp"
    )

    fun normalizeMime(mimeType: String): String = mimeType.trim().lowercase(java.util.Locale.ROOT)

    fun isAcceptedMime(mimeType: String): Boolean = normalizeMime(mimeType) in ACCEPTED_MIME_TYPES

    /** True when a frame of this media type may arrive over the given kind of link. */
    fun isTransportAllowed(mimeType: String, viaWifiDirect: Boolean): Boolean =
        (requiredTransport(mimeType) == RequiredTransport.WIFI_DIRECT) == viaWifiDirect

    fun requiredTransport(mimeType: String): RequiredTransport =
        if (normalizeMime(mimeType).startsWith("video/")) {
            RequiredTransport.WIFI_DIRECT
        } else {
            RequiredTransport.BLUETOOTH_LE
        }
}
