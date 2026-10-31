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

    /** An ACK is valid only for the exact stable logical recipient saved with the transfer. */
    fun isAcknowledgementFromRecipient(recipientId: String?, authenticatedSenderNodeId: String): Boolean {
        val canonicalNodeId = runCatching { java.util.UUID.fromString(authenticatedSenderNodeId).toString() }.getOrNull()
        return canonicalNodeId == authenticatedSenderNodeId && recipientId == "mesh:$authenticatedSenderNodeId"
    }

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


/**
 * Bounds work caused by frames arriving through one physical neighbor and across the whole app.
 * Call this before cryptographic verification. The tracked-peer map is itself bounded.
 */
internal class IngressRateLimiter(
    private val maxPerPeer: Int,
    private val maxGlobal: Int,
    private val windowMs: Long = 60_000L,
    private val maxTrackedPeers: Int = 128
) {
    private data class Window(var startedAt: Long, var accepted: Int)

    private val peers = LinkedHashMap<String, Window>()
    private var globalStartedAt = 0L
    private var globalAccepted = 0

    init {
        require(maxPerPeer > 0 && maxGlobal > 0 && windowMs > 0L && maxTrackedPeers > 0)
    }

    @Synchronized
    fun allow(peerId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        allowBatch(peerId, 1, nowMs)

    /**
     * Atomically reserve a group of envelopes. A split chat message is accepted or paused as a
     * whole; it cannot consume part of the receiver's budget and then lose its final chunks.
     */
    @Synchronized
    fun allowBatch(peerId: String, count: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (peerId.isBlank() || count !in 1..maxPerPeer || count > maxGlobal) return false
        if (globalStartedAt == 0L || nowMs < globalStartedAt || nowMs - globalStartedAt >= windowMs) {
            globalStartedAt = nowMs
            globalAccepted = 0
        }
        peers.entries.removeAll { nowMs < it.value.startedAt || nowMs - it.value.startedAt >= windowMs }

        var peerWindow = peers[peerId]
        if (peerWindow == null) {
            while (peers.size >= maxTrackedPeers) {
                val oldest = peers.keys.firstOrNull() ?: break
                peers.remove(oldest)
            }
            peerWindow = Window(nowMs, 0)
            peers[peerId] = peerWindow
        }
        if (peerWindow.accepted + count > maxPerPeer || globalAccepted + count > maxGlobal) return false

        peerWindow.accepted += count
        globalAccepted += count
        return true
    }
}
