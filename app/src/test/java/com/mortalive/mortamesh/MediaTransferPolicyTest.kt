package com.mortalive.mortamesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTransferPolicyTest {
    @Test fun photosAlwaysUseBluetooth() {
        assertEquals(MediaTransferPolicy.RequiredTransport.BLUETOOTH_LE, MediaTransferPolicy.requiredTransport("image/jpeg"))
        assertEquals(MediaTransferPolicy.RequiredTransport.BLUETOOTH_LE, MediaTransferPolicy.requiredTransport("image/webp"))
        assertEquals(MediaTransferPolicy.RequiredTransport.BLUETOOTH_LE, MediaTransferPolicy.requiredTransport("image/png"))
    }

    @Test fun videosAlwaysRequireWifiDirect() {
        assertEquals(MediaTransferPolicy.RequiredTransport.WIFI_DIRECT, MediaTransferPolicy.requiredTransport("video/mp4"))
        assertEquals(MediaTransferPolicy.RequiredTransport.WIFI_DIRECT, MediaTransferPolicy.requiredTransport("VIDEO/MP4"))
        assertEquals(MediaTransferPolicy.RequiredTransport.WIFI_DIRECT, MediaTransferPolicy.requiredTransport("video/3gpp"))
    }

    @Test fun acceptsOnlyTheMediaTypesTheAppProduces() {
        assertTrue(MediaTransferPolicy.isAcceptedMime("image/jpeg"))
        assertTrue(MediaTransferPolicy.isAcceptedMime(" IMAGE/WEBP "))
        assertTrue(MediaTransferPolicy.isAcceptedMime("video/mp4"))
        assertFalse(MediaTransferPolicy.isAcceptedMime("application/octet-stream"))
        assertFalse(MediaTransferPolicy.isAcceptedMime("application/vnd.android.package-archive"))
        assertFalse(MediaTransferPolicy.isAcceptedMime(""))
    }

    @Test fun normalizationDoesNotChangeTransportChoice() {
        assertEquals(MediaTransferPolicy.RequiredTransport.WIFI_DIRECT, MediaTransferPolicy.requiredTransport("  Video/MP4 "))
        assertEquals(MediaTransferPolicy.RequiredTransport.BLUETOOTH_LE, MediaTransferPolicy.requiredTransport(" Image/JPEG"))
    }

    @Test fun transportRuleIsEnforcedForReceivedMedia() {
        assertTrue(MediaTransferPolicy.isTransportAllowed("image/jpeg", viaWifiDirect = false))
        assertFalse(MediaTransferPolicy.isTransportAllowed("image/jpeg", viaWifiDirect = true))
        assertTrue(MediaTransferPolicy.isTransportAllowed("video/mp4", viaWifiDirect = true))
        assertFalse(MediaTransferPolicy.isTransportAllowed("VIDEO/MP4", viaWifiDirect = false))
    }
}


class IngressRateLimiterTest {
    @Test fun enforcesPerPeerAndGlobalBudgets() {
        val limiter = IngressRateLimiter(maxPerPeer = 2, maxGlobal = 3, windowMs = 1_000L)
        assertTrue(limiter.allow("peer-a", 10L))
        assertTrue(limiter.allow("peer-a", 11L))
        assertFalse(limiter.allow("peer-a", 12L))
        assertTrue(limiter.allow("peer-b", 13L))
        assertFalse(limiter.allow("peer-c", 14L))
    }

    @Test fun resetsTheWindowAndBoundsTrackedPeers() {
        val limiter = IngressRateLimiter(maxPerPeer = 1, maxGlobal = 10, windowMs = 100L, maxTrackedPeers = 2)
        assertTrue(limiter.allow("peer-a", 10L))
        assertFalse(limiter.allow("peer-a", 20L))
        assertTrue(limiter.allow("peer-a", 110L))
        assertTrue(limiter.allow("peer-b", 111L))
        assertTrue(limiter.allow("peer-c", 112L))
        assertFalse(limiter.allow("peer-b", 113L))
    }
}
