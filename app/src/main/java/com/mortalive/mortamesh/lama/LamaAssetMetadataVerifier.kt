package com.mortalive.mortamesh.lama

import java.security.MessageDigest

/** Verifies raw metadata bytes against the SHA-256 commitment in a LAMA asset record. */
object LamaAssetMetadataVerifier {
    enum class Result { MATCH, HASH_MISMATCH, INVALID_EXPECTED_HASH, TOO_LARGE }
    fun verify(metadataBytes: ByteArray, expectedHashHex: String, maxBytes: Int): Result {
        if (!PayHex.isTxid(expectedHashHex)) return Result.INVALID_EXPECTED_HASH
        if (maxBytes < 0 || metadataBytes.size > maxBytes) return Result.TOO_LARGE
        val actual = PayHex.encode(MessageDigest.getInstance("SHA-256").digest(metadataBytes))
        return if (actual == expectedHashHex) Result.MATCH else Result.HASH_MISMATCH
    }
    fun sha256Hex(metadataBytes: ByteArray): String =
        PayHex.encode(MessageDigest.getInstance("SHA-256").digest(metadataBytes))
}
