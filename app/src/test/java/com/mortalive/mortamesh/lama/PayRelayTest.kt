package com.mortalive.mortamesh.lama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayRelayTest {
    private fun txid(n: Int) = n.toString(16).padStart(64, '0')
    private fun hex(bytes: Int) = "ab".repeat(bytes)

    @Test fun hexIsDecodedStrictly() {
        assertEquals(listOf(10, 255), PayHex.decode("0aff", 10)?.map { it.toInt() and 0xff })
        assertNull(PayHex.decode("0AFF", 10))
        assertNull(PayHex.decode("0af", 10))
        assertNull(PayHex.decode("zz", 10))
        assertNull(PayHex.decode("", 10))
        assertNull(PayHex.decode("00".repeat(11), 10))
        assertNull(PayHex.decode("00", 0))
        assertEquals("0aff", PayHex.encode(byteArrayOf(0x0a, 0xff.toByte())))
        assertTrue(PayHex.isTxid(txid(255)))
        assertFalse(PayHex.isTxid("ABC"))
    }
    @Test fun storeRequiresCoreValidationAndDeduplicates() {
        val store = PayTxStore(2, 100, 1_000L, validateWithCore = { value, network ->
            assertEquals(LamaNetwork.TESTNET, network)
            if (value == hex(10)) RelayCheck(true, txid(1), null) else RelayCheck(false, null, "invalid transaction")
        }, now = { 0L })
        assertEquals(PayTxStore.Result.STORED, store.offer(hex(10), LamaNetwork.TESTNET))
        assertEquals(PayTxStore.Result.DUPLICATE, store.offer(hex(10), LamaNetwork.TESTNET))
        assertEquals(PayTxStore.Result.REJECTED_INVALID, store.offer("xyz", LamaNetwork.TESTNET))
        assertEquals(PayTxStore.Result.REJECTED_INVALID, store.offer(hex(11), LamaNetwork.TESTNET))
        assertEquals(1, store.size())
    }
    @Test fun capacityRejectsNewItemInsteadOfEvictingExistingPayment() {
        val store = PayTxStore(1, 10, 1_000L,
            validateWithCore = { value, _ -> RelayCheck(true, if (value == hex(5)) txid(1) else txid(2), null) },
            now = { 0L })
        assertEquals(PayTxStore.Result.STORED, store.offer(hex(5), LamaNetwork.TESTNET))
        assertEquals(PayTxStore.Result.REJECTED_CAPACITY, store.offer(hex(5) + "ab", LamaNetwork.TESTNET))
        assertTrue(store.contains(txid(1)))
        assertFalse(store.contains(txid(2)))
        assertEquals(5, store.bytesStored())
    }
    @Test fun broadcastMarkingIsNotConfirmationAndNetworkIsolated() {
        var time = 0L
        val store = PayTxStore(5, 1_000, 1_000L, validateWithCore = { _, _ -> RelayCheck(true, txid(1), null) }, now = { time })
        store.offer(hex(4), LamaNetwork.TESTNET)
        assertEquals(1, store.pendingForBroadcast(LamaNetwork.TESTNET).size)
        assertTrue(store.pendingForBroadcast(LamaNetwork.DEVNET).isEmpty())
        store.markBroadcast(txid(1))
        assertTrue(store.pendingForBroadcast(LamaNetwork.TESTNET).isEmpty())
        assertTrue(store.contains(txid(1)))
        time = 1_000L
        store.prune()
        assertEquals(0, store.size())
        assertEquals(0, store.bytesStored())
    }
    @Test fun paymentMemoIsVersionedAndProvisional() {
        val first = LamaPayMemo.generate()
        val second = LamaPayMemo.generate()
        assertTrue(LamaPayMemo.isValid(first))
        assertEquals(32, first.size)
        assertFalse(first.contentEquals(second))
        assertFalse(LamaPayMemo.isValid(ByteArray(32)))
        assertFalse(LamaPayMemo.isValid(ByteArray(31)))
    }
    @Test fun missingCoreFailsLoudly() {
        assertFalse(LamaCoreUnavailable.isAvailable)
        try {
            LamaCoreUnavailable.validateForRelay(hex(1), LamaNetwork.TESTNET)
            throw AssertionError("expected a missing-core exception")
        } catch (_: LamaCoreMissingException) { }
    }
}
