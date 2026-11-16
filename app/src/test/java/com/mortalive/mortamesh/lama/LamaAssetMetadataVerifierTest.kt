package com.mortalive.mortamesh.lama

import org.junit.Assert.assertEquals
import org.junit.Test

class LamaAssetMetadataVerifierTest {
    @Test fun verifiesExactBytesAgainstAssetHash() {
        val bytes = "{\"name\":\"test nft\"}".toByteArray()
        val hash = LamaAssetMetadataVerifier.sha256Hex(bytes)
        assertEquals(LamaAssetMetadataVerifier.Result.MATCH, LamaAssetMetadataVerifier.verify(bytes, hash, 1024))
        assertEquals(LamaAssetMetadataVerifier.Result.HASH_MISMATCH, LamaAssetMetadataVerifier.verify("tampered".toByteArray(), hash, 1024))
    }
    @Test fun rejectsMalformedHashAndOversizedMetadata() {
        val bytes = byteArrayOf(1, 2, 3)
        assertEquals(LamaAssetMetadataVerifier.Result.INVALID_EXPECTED_HASH, LamaAssetMetadataVerifier.verify(bytes, "not-a-hash", 100))
        assertEquals(LamaAssetMetadataVerifier.Result.TOO_LARGE, LamaAssetMetadataVerifier.verify(bytes, "0".repeat(64), 2))
    }
}
