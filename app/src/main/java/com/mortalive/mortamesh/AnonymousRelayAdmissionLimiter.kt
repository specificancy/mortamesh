package com.mortalive.mortamesh

import java.util.LinkedHashMap
import java.util.UUID

/**
 * Limits the number and declared bytes of distinct anonymous transfer IDs accepted from one
 * immediate Wi-Fi Direct neighbor per window. Once admitted, all chunks for that transfer ID
 * are allowed through the window so a large transfer can finish.
 *
 * This is an admission/abuse throttle, not an identity or reputation system.
 */
internal class AnonymousRelayAdmissionLimiter(
    private val maxTransfersPerPeer: Int = 8,
    private val maxBytesPerPeer: Long = 250L * 1024L * 1024L,
    private val windowMs: Long = 60L * 60L * 1000L,
    private val maxTrackedPeers: Int = 128
) {
    private data class Window(
        var startedAt: Long,
        val transferBytes: LinkedHashMap<String, Long> = LinkedHashMap()
    )

    private val peers = LinkedHashMap<String, Window>()

    init {
        require(maxTransfersPerPeer > 0 && maxBytesPerPeer > 0L && windowMs > 0L && maxTrackedPeers > 0)
    }

    @Synchronized
    fun allow(peerId: String, transferId: String, declaredBytes: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (peerId.isBlank() || !isCanonicalUuid(transferId) ||
            declaredBytes !in 16L..(AnonymousSubmissionPolicy.MAX_ATTACHMENT_BYTES + 1024L * 1024L)) return false

        peers.entries.removeAll { nowMs < it.value.startedAt || nowMs - it.value.startedAt >= windowMs }
        var window = peers[peerId]
        if (window == null) {
            while (peers.size >= maxTrackedPeers) {
                val oldest = peers.keys.firstOrNull() ?: break
                peers.remove(oldest)
            }
            window = Window(nowMs)
            peers[peerId] = window
        }

        // Subsequent chunks of an already-admitted transfer must not consume the quota again.
        if (window.transferBytes.containsKey(transferId)) return true
        if (window.transferBytes.size >= maxTransfersPerPeer) return false
        val acceptedBytes = window.transferBytes.values.sum()
        if (acceptedBytes + declaredBytes > maxBytesPerPeer) return false
        window.transferBytes[transferId] = declaredBytes
        return true
    }

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: Exception) { false }
}
