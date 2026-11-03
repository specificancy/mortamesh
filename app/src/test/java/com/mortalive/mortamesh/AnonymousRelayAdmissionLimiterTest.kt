package com.mortalive.mortamesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnonymousRelayAdmissionLimiterTest {
    private val transferA = "123e4567-e89b-42d3-a456-426614174000"
    private val transferB = "123e4567-e89b-42d3-a456-426614174001"
    private val transferC = "123e4567-e89b-42d3-a456-426614174002"

    @Test fun allowsAllChunksOfAnAdmittedTransferWithoutRecountingIt() {
        val limiter = AnonymousRelayAdmissionLimiter(maxTransfersPerPeer = 1, maxBytesPerPeer = 100L, windowMs = 1_000L)
        assertTrue(limiter.allow("wifi:peer-a", transferA, 80L, 10L))
        assertTrue(limiter.allow("wifi:peer-a", transferA, 80L, 11L))
        assertFalse(limiter.allow("wifi:peer-a", transferB, 10L, 12L))
    }

    @Test fun boundsDeclaredBytesAndTransferCountPerPeer() {
        val limiter = AnonymousRelayAdmissionLimiter(maxTransfersPerPeer = 2, maxBytesPerPeer = 100L, windowMs = 1_000L)
        assertTrue(limiter.allow("wifi:peer-a", transferA, 60L, 10L))
        assertFalse(limiter.allow("wifi:peer-a", transferB, 50L, 11L))
        assertTrue(limiter.allow("wifi:peer-a", transferB, 40L, 12L))
        assertFalse(limiter.allow("wifi:peer-a", transferC, 1L, 13L))
        assertTrue(limiter.allow("wifi:peer-b", transferC, 100L, 14L))
    }

    @Test fun expiresWindowsAndRejectsInvalidIdsOrSizes() {
        val limiter = AnonymousRelayAdmissionLimiter(maxTransfersPerPeer = 1, maxBytesPerPeer = 100L, windowMs = 100L)
        assertTrue(limiter.allow("peer", transferA, 50L, 10L))
        assertFalse(limiter.allow("peer", transferB, 50L, 20L))
        assertTrue(limiter.allow("peer", transferB, 50L, 110L))
        assertFalse(limiter.allow("peer", "not-an-id", 1L, 111L))
        assertFalse(limiter.allow("peer", transferC, -1L, 112L))
    }
}
