package com.mortalive.mortamesh

/** Pure Wi-Fi Direct session rules, kept Android-free for JVM testing. */
object WifiSessionPolicy {
    const val MAX_CLIENTS = 4
    const val APPROVAL_TTL_MS = 120_000L
    const val RECENT_INVITE_MS = 20_000L
    enum class HostState { NONE, CREATING, READY }

    fun hostState(creating: Boolean, hostingFlag: Boolean, isOwner: Boolean, formed: Boolean): HostState = when {
        hostingFlag && isOwner && formed -> HostState.READY
        creating || hostingFlag -> HostState.CREATING
        else -> HostState.NONE
    }

    fun higherIdAccepts(localNodeId: String, peerNodeId: String): Boolean = localNodeId > peerNodeId
    fun hasRoom(connectedClients: Int, waitingApproved: Int, maxClients: Int = MAX_CLIENTS): Boolean =
        connectedClients + waitingApproved < maxClients
    fun isApproved(approvals: Map<String, Long>, nodeId: String, now: Long): Boolean =
        (approvals[nodeId] ?: 0L) > now
    fun pruneExpired(approvals: MutableMap<String, Long>, now: Long) {
        approvals.entries.removeAll { it.value <= now }
    }
    fun inviteRecentlySent(expiresAt: Long?, now: Long): Boolean =
        expiresAt != null && expiresAt - now > APPROVAL_TTL_MS - RECENT_INVITE_MS
    fun failureMayTearDownGroup(state: HostState, connectedClients: Int, otherInvites: Int): Boolean =
        connectedClients <= 0 && otherInvites <= 0
}
