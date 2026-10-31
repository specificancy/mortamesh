package com.mortalive.mortamesh

/**
 * Media routing is based on media type, never on file size.
 * When the required transport is unavailable, queue the item instead of falling back.
 */
internal object MediaTransferPolicy {
    enum class RequiredTransport { BLUETOOTH_LE, WIFI_DIRECT }

    fun requiredTransport(mimeType: String): RequiredTransport =
        if (mimeType.trim().lowercase(java.util.Locale.ROOT).startsWith("video/")) {
            RequiredTransport.WIFI_DIRECT
        } else {
            RequiredTransport.BLUETOOTH_LE
        }
}
