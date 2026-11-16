package com.mortalive.mortamesh.lama

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LamaAddressTest {
    private val mainnet = "CFEPwFerR8NfifaVteZPubwo6TMGJXToqdrCFvkpvCbJZDwzjoyN"
    private val testnet = "DRsSWYY3pMhmWogeKFiYGzhh5vxUysdd9AqEjZXar2czosbL9eF2"
    private val devnet = "B4bMMxmf1u3ZvXUMU3QFYDBu6yk3dBHzY6s9nHz4zNZcJaRoHcJe"

    @Test fun validAddressesPassOnTheirOwnNetwork() {
        assertNull(LamaAddress.validate(mainnet, LamaNetwork.MAINNET))
        assertNull(LamaAddress.validate(testnet, LamaNetwork.TESTNET))
        assertNull(LamaAddress.validate(devnet, LamaNetwork.DEVNET))
        assertNull(LamaAddress.validate("  $testnet \n", LamaNetwork.TESTNET))
    }
    @Test fun wrongNetworkIsRefusedAndNamed() {
        assertEquals(LamaAddress.Problem.WRONG_NETWORK, LamaAddress.validate(testnet, LamaNetwork.MAINNET))
        assertEquals(LamaAddress.Problem.WRONG_NETWORK, LamaAddress.validate(devnet, LamaNetwork.TESTNET))
        assertEquals(LamaNetwork.TESTNET, LamaAddress.networkOf(testnet))
        assertEquals(LamaNetwork.MAINNET, LamaAddress.networkOf(mainnet))
        assertNull(LamaAddress.networkOf("DRsSWYY3pMhmWogeKFiYGzhh5vxUysdd9AqEjZXar2czosbL9eF3"))
        assertNull(LamaAddress.networkOf("DRtwHEyUvcZgVUdeEsGYk7nLKPKcAVkbGv7fbHSaiUF4ZYSebMEd"))
    }
    @Test fun typosAndGarbageAreCaught() {
        assertEquals(LamaAddress.Problem.BAD_CHECKSUM, LamaAddress.validate("DRsSWYY3pMhmWogeKFiYGzhh5vxUysdd9AqEjZXar2czosbL9eF3", LamaNetwork.TESTNET))
        assertEquals(LamaAddress.Problem.BAD_CHARACTERS, LamaAddress.validate("0OIl" + testnet.drop(4), LamaNetwork.TESTNET))
        assertEquals(LamaAddress.Problem.BAD_LENGTH, LamaAddress.validate("StV1DL6CwTryKyV", LamaNetwork.TESTNET))
        assertEquals(LamaAddress.Problem.BAD_LENGTH, LamaAddress.validate("x".repeat(65), LamaNetwork.TESTNET))
        assertEquals(LamaAddress.Problem.EMPTY, LamaAddress.validate("   ", LamaNetwork.TESTNET))
    }
    @Test fun unsupportedSignatureSchemeIsRefused() {
        assertEquals(LamaAddress.Problem.UNSUPPORTED_SCHEME, LamaAddress.validate("DRtwHEyUvcZgVUdeEsGYk7nLKPKcAVkbGv7fbHSaiUF4ZYSebMEd", LamaNetwork.TESTNET))
    }
    @Test fun addressDisplayGroupingIsReversible() {
        val grouped = LamaAddress.groupForDisplay(testnet)
        assertEquals(testnet, grouped.replace(" ", ""))
        assertEquals("DRsS WYY3", grouped.take(9))
    }
    @Test fun base58MatchesIndependentVectors() {
        assertEquals("1", Base58.encode(byteArrayOf(0)))
        assertEquals("112", Base58.encode(byteArrayOf(0, 0, 1)))
        assertEquals("StV1DL6CwTryKyV", Base58.encode("hello world".toByteArray()))
        assertArrayEquals("hello world".toByteArray(), Base58.decode("StV1DL6CwTryKyV"))
        assertArrayEquals(byteArrayOf(0, 0, 1), Base58.decode("112"))
        assertArrayEquals(ByteArray(0), Base58.decode(""))
    }
}
