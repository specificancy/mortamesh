package com.mortalive.mortamesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WalletIsolationPolicyTest {
    @Test
    fun bitcoinAndLamaUseDifferentAuthenticatedEncryptionScopes() {
        val bitcoin = WalletSecretEnvelopeCodec.aad("primary", "bitcoin_testnet4")
        val lama = WalletSecretEnvelopeCodec.aad("primary", "lama_testnet")

        assertNotEquals(bitcoin.toList(), lama.toList())
    }

    @Test
    fun socialIdentityIsNotPartOfWalletSecretScope() {
        val walletScope = WalletSecretEnvelopeCodec.aad("primary", "bitcoin_testnet4")
        val socialScope = "Mortalive|account|group|wallet".toByteArray()

        assertFalse(walletScope.contentEquals(socialScope))
    }
}
