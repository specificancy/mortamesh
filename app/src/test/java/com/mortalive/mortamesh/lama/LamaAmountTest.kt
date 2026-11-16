package com.mortalive.mortamesh.lama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LamaAmountTest {
    @Test fun formatsWithoutFloatingPoint() {
        assertEquals("1", LamaAmount.format(100_000_000L))
        assertEquals("1.5", LamaAmount.format(150_000_000L))
        assertEquals("0.00000001", LamaAmount.format(1L))
        assertEquals("0.00004", LamaAmount.format(4_000L))
        assertEquals("88888888", LamaAmount.format(88_888_888L * 100_000_000L))
    }
    @Test fun parsesPlainDecimalsWithAtMostEightPlaces() {
        assertEquals(150_000_000L, LamaAmount.parse("1.5"))
        assertEquals(1L, LamaAmount.parse("0.00000001"))
        assertEquals(0L, LamaAmount.parse("0"))
        assertNull(LamaAmount.parse("0.000000001"))
        assertNull(LamaAmount.parse("-1"))
        assertNull(LamaAmount.parse("1e3"))
        assertNull(LamaAmount.parse("1,5"))
        assertNull(LamaAmount.parse(""))
        assertNull(LamaAmount.parse("88888889"))
        assertNull(LamaAmount.parsePositive("0"))
    }
    @Test fun roundTripsAndFeeFloorUseLays() {
        for (lays in listOf(1L, 999L, 1_000L, 12_345_678L, 100_000_000L, 987_654_321_000L)) {
            assertEquals(lays, LamaAmount.parse(LamaAmount.format(lays)))
        }
        assertEquals(1_000L, LamaAmount.feeFloorForSize(300))
        assertEquals(3_900L, LamaAmount.feeFloorForSize(3_900))
        assertTrue(LamaAmount.isDust(999L))
        assertFalse(LamaAmount.isDust(1_000L))
    }
    @Test(expected = IllegalArgumentException::class)
    fun negativeTransactionSizeIsRejected() { LamaAmount.feeFloorForSize(-1) }
    @Test fun mainnetIsOffAndTestnetIsDefault() {
        assertEquals(LamaNetwork.TESTNET, LamaNetwork.DEFAULT)
        assertFalse(LamaNetwork.selectable().contains(LamaNetwork.MAINNET))
    }
}
