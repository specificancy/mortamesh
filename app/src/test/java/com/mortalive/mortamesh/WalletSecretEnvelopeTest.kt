package com.mortalive.mortamesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletSecretEnvelopeTest {
    @Test
    fun envelopeRoundTripsIvAndCiphertext() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(48) { (it * 3).toByte() }

        val decoded = WalletSecretEnvelopeCodec.decode(WalletSecretEnvelopeCodec.encode(iv, ciphertext))

        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun envelopeRejectsUnknownMagicAndVersion() {
        val valid = WalletSecretEnvelopeCodec.encode(ByteArray(12), ByteArray(16))
        val badMagic = valid.copyOf().also { it[0] = 0 }
        val badVersion = valid.copyOf().also { it[4] = 2 }

        assertThrows(IllegalArgumentException::class.java) {
            WalletSecretEnvelopeCodec.decode(badMagic)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalletSecretEnvelopeCodec.decode(badVersion)
        }
    }

    @Test
    fun additionalAuthenticatedDataIsBoundToWalletAndNetwork() {
        val original = WalletSecretEnvelopeCodec.aad("primary", "bitcoin_testnet")

        assertTrue(!original.contentEquals(WalletSecretEnvelopeCodec.aad("primary", "lama_testnet")))
        assertTrue(!original.contentEquals(WalletSecretEnvelopeCodec.aad("other", "bitcoin_testnet")))
    }

    @Test
    fun envelopeRejectsTruncatedCiphertext() {
        assertThrows(IllegalArgumentException::class.java) {
            WalletSecretEnvelopeCodec.decode(ByteArray(6 + 12 + 15))
        }
    }
}
