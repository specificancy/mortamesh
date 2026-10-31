package com.mortalive.mortamesh

import org.junit.Assert.assertEquals
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
}
