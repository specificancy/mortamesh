package com.mortalive.mortamesh.lama

import org.junit.Assert.*
import org.junit.Test

class LamaWalletFoundationTest {
    private val mainnet = "CFEPwFerR8NfifaVteZPubwo6TMGJXToqdrCFvkpvCbJZDwzjoyN"
    private val testnet = "DRsSWYY3pMhmWogeKFiYGzhh5vxUysdd9AqEjZXar2czosbL9eF2"
    private val devnet = "B4bMMxmf1u3ZvXUMU3QFYDBu6yk3dBHzY6s9nHz4zNZcJaRoHcJe"

    @Test fun networksMatchRustAndMainnetIsDisabled() {
        assertEquals(1, LamaNetwork.MAINNET.chainId)
        assertEquals(2, LamaNetwork.TESTNET.chainId)
        assertEquals(3, LamaNetwork.DEVNET.chainId)
        assertEquals(0x4C, LamaNetwork.MAINNET.addressVersion)
        assertEquals(0x54, LamaNetwork.TESTNET.addressVersion)
        assertEquals(0x44, LamaNetwork.DEVNET.addressVersion)
        assertEquals(LamaNetwork.TESTNET, LamaNetwork.DEFAULT)
        assertFalse(LamaNetwork.selectable().contains(LamaNetwork.MAINNET))
    }

    @Test fun addressChecksumsAndNetworkVersionsAreValidated() {
        assertNull(LamaAddress.validate(mainnet, LamaNetwork.MAINNET))
        assertNull(LamaAddress.validate(testnet, LamaNetwork.TESTNET))
        assertNull(LamaAddress.validate(devnet, LamaNetwork.DEVNET))
        assertEquals(LamaAddress.Problem.WRONG_NETWORK, LamaAddress.validate(testnet, LamaNetwork.DEVNET))
        assertEquals(LamaAddress.Problem.BAD_CHECKSUM,
            LamaAddress.validate("DRsSWYY3pMhmWogeKFiYGzhh5vxUysdd9AqEjZXar2czosbL9eF3", LamaNetwork.TESTNET))
        assertEquals(LamaNetwork.TESTNET, LamaAddress.networkOf(testnet))
        assertEquals(LamaNetwork.MAINNET, LamaAddress.networkOf(mainnet))
    }

    @Test fun rejectsBadAddressAndUnsupportedSchemes() {
        assertEquals(LamaAddress.Problem.EMPTY, LamaAddress.validate(" ", LamaNetwork.TESTNET))
        assertEquals(LamaAddress.Problem.BAD_CHARACTERS, LamaAddress.validate("0OIl", LamaNetwork.TESTNET))
        assertEquals(LamaAddress.Problem.BAD_LENGTH, LamaAddress.validate("StV1DL6CwTryKyV", LamaNetwork.TESTNET))
        assertEquals(LamaAddress.Problem.UNSUPPORTED_SCHEME,
            LamaAddress.validate("DRtwHEyUvcZgVUdeEsGYk7nLKPKcAVkbGv7fbHSaiUF4ZYSebMEd", LamaNetwork.TESTNET))
    }

    @Test fun amountParsingUsesIntegerLaysAndRejectsAmbiguousValues() {
        assertEquals(150_000_000L, LamaAmount.parse("1.5"))
        assertEquals(1L, LamaAmount.parse("0.00000001"))
        assertEquals("1.5", LamaAmount.format(150_000_000L))
        assertEquals("0.00000001", LamaAmount.format(1L))
        assertNull(LamaAmount.parse("0.000000001"))
        assertNull(LamaAmount.parse("-1"))
        assertNull(LamaAmount.parse("1e3"))
        assertNull(LamaAmount.parse("88888889"))
        assertEquals(1_000L, LamaAmount.minimumRelayFee(300))
        assertEquals(4_000L, LamaAmount.minimumRelayFee(4_000))
    }

    @Test fun verifiesAssetMetadataBytesAgainstCommittedHash() {
        val bytes = "{\"name\":\"sample\"}".toByteArray()
        val hash = LamaAssetMetadataVerifier.sha256Hex(bytes)
        assertEquals(LamaAssetMetadataVerifier.Result.MATCH, LamaAssetMetadataVerifier.verify(bytes, hash, 1024))
        assertEquals(LamaAssetMetadataVerifier.Result.HASH_MISMATCH,
            LamaAssetMetadataVerifier.verify("modified".toByteArray(), hash, 1024))
        assertEquals(LamaAssetMetadataVerifier.Result.TOO_LARGE,
            LamaAssetMetadataVerifier.verify(bytes, hash, 2))
    }

    @Test fun txQueueRequiresValidatorAndNeverEvictsToMakeSpace() {
        val txid = "1".repeat(64)
        val queue = PayTxStore(1, 10, 10, 1000, validate = { _, _ -> RelayValidation(true, txid, null) }, now = { 0L })
        assertEquals(PayTxStore.OfferResult.STORED, queue.offer("ab".repeat(5), LamaNetwork.TESTNET))
        assertEquals(PayTxStore.OfferResult.DUPLICATE, queue.offer("ab".repeat(5), LamaNetwork.TESTNET))
        assertEquals(PayTxStore.OfferResult.CAPACITY, queue.offer("cd".repeat(5), LamaNetwork.TESTNET))
        assertEquals(1, queue.size())
        assertEquals(10, queue.storedBytes())
    }

    @Test fun txQueueRejectsInvalidHexAndValidatorFailures() {
        val queue = PayTxStore(2, 100, 20, 1000, validate = { _, _ -> RelayValidation(false, null, "bad tx") })
        assertEquals(PayTxStore.OfferResult.INVALID, queue.offer("zz", LamaNetwork.TESTNET))
        assertEquals(PayTxStore.OfferResult.INVALID, queue.offer("ab", LamaNetwork.TESTNET))
    }

    @Test fun txQueueExpiresOldEntries() {
        var time = 0L
        val queue = PayTxStore(2, 100, 20, 1000, validate = { _, _ -> RelayValidation(true, "2".repeat(64), null) }, now = { time })
        assertEquals(PayTxStore.OfferResult.STORED, queue.offer("ab", LamaNetwork.TESTNET))
        time = 1000
        queue.prune()
        assertEquals(0, queue.size())
        assertEquals(0, queue.storedBytes())
    }
}
