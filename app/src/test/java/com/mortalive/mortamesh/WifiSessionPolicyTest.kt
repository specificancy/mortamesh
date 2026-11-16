package com.mortalive.mortamesh

import com.mortalive.mortamesh.WifiSessionPolicy.HostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSessionPolicyTest {
    @Test fun simultaneousRequestsLetExactlyOnePhoneGiveWay() {
        assertTrue(WifiSessionPolicy.higherIdAccepts("b-node", "a-node"))
        assertFalse(WifiSessionPolicy.higherIdAccepts("a-node", "b-node"))
        assertFalse(WifiSessionPolicy.higherIdAccepts("same", "same"))
    }
    @Test fun requestsDuringGroupCreationAreNotTreatedAsReadyHost() {
        assertEquals(HostState.NONE, WifiSessionPolicy.hostState(false, false, false, false))
        assertEquals(HostState.CREATING, WifiSessionPolicy.hostState(true, false, false, false))
        assertEquals(HostState.CREATING, WifiSessionPolicy.hostState(true, true, false, false))
        assertEquals(HostState.CREATING, WifiSessionPolicy.hostState(false, true, true, false))
        assertEquals(HostState.READY, WifiSessionPolicy.hostState(true, true, true, true))
        assertEquals(HostState.READY, WifiSessionPolicy.hostState(false, true, true, true))
    }
    @Test fun duplicateInvitationsAreDetectedOnlyWhenFresh() {
        val now = 1_000_000L
        assertTrue(WifiSessionPolicy.inviteRecentlySent(now + WifiSessionPolicy.APPROVAL_TTL_MS, now))
        assertFalse(WifiSessionPolicy.inviteRecentlySent(now + 60_000L, now))
        assertFalse(WifiSessionPolicy.inviteRecentlySent(null, now))
    }
    @Test fun failedJoinKeepsGroupWhenAnotherPersonIsConnectedOrInvited() {
        assertFalse(WifiSessionPolicy.failureMayTearDownGroup(HostState.READY, 1, 0))
        assertFalse(WifiSessionPolicy.failureMayTearDownGroup(HostState.READY, 0, 1))
        assertTrue(WifiSessionPolicy.failureMayTearDownGroup(HostState.READY, 0, 0))
        assertFalse(WifiSessionPolicy.failureMayTearDownGroup(HostState.CREATING, 0, 1))
        assertFalse(WifiSessionPolicy.failureMayTearDownGroup(HostState.CREATING, 1, 0))
        assertTrue(WifiSessionPolicy.failureMayTearDownGroup(HostState.CREATING, 0, 0))
    }
    @Test fun expiredApprovalsArePruned() {
        val approvals = mutableMapOf("old" to 500L, "edge" to 1_000L, "fresh" to 5_000L)
        WifiSessionPolicy.pruneExpired(approvals, 1_000L)
        assertEquals(setOf("fresh"), approvals.keys)
        assertFalse(WifiSessionPolicy.isApproved(approvals, "old", 1_000L))
        assertTrue(WifiSessionPolicy.isApproved(approvals, "fresh", 1_000L))
    }
    @Test fun reservationsCountAgainstCapacityAndUnapprovedPeersFail() {
        val max = WifiSessionPolicy.MAX_CLIENTS
        assertTrue(WifiSessionPolicy.hasRoom(max - 1, 0))
        assertFalse(WifiSessionPolicy.hasRoom(max, 0))
        assertFalse(WifiSessionPolicy.hasRoom(max - 2, 2))
        val approvals = mapOf("invited" to 2_000L)
        assertFalse(WifiSessionPolicy.isApproved(approvals, "stranger", 1_000L))
        assertFalse(WifiSessionPolicy.isApproved(approvals, "invited", 2_000L))
        assertTrue(WifiSessionPolicy.isApproved(approvals, "invited", 1_999L))
    }
}
