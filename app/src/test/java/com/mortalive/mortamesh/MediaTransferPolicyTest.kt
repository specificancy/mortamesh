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
