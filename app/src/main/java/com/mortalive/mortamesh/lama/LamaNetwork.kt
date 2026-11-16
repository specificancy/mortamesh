package com.mortalive.mortamesh.lama

/** Values synchronized with current LAMA Rust params. Mainnet remains disabled in this pre-release app. */
enum class LamaNetwork(val chainId: Int, val netVersion: Int, val label: String, val isTest: Boolean) {
    MAINNET(1, 0x4C, "Mainnet", false),
    TESTNET(2, 0x54, "Testnet", true),
    DEVNET(3, 0x44, "Devnet", true);

    companion object {
        const val PREFERENCES_NAME = "lama_wallet_settings_v1"
        const val NETWORK_CHAIN_ID_KEY = "selected_chain_id"
        val DEFAULT = TESTNET
        const val MAINNET_ENABLED = false
        fun fromChainId(chainId: Int): LamaNetwork? = values().firstOrNull { it.chainId == chainId }
        fun selectable(): List<LamaNetwork> = values().filter { MAINNET_ENABLED || it.isTest }
    }
}

/** Protocol and receiver caps copied from the LAMA context and Rust source. */
object LamaConstants {
    const val LAYS_PER_LAMA = 100_000_000L
    const val MIN_RELAY_FEE_LAYS = 1_000L
    const val DUST_LIMIT_LAYS = 1_000L
    const val ASSET_OUT_MIN_VALUE_LAYS = 10_000L
    const val MAX_MEMO_BYTES = 64
    const val MAX_INPUTS_PER_TX = 400
    const val COINBASE_MATURITY_BLOCKS = 100
    const val MAX_SUPPLY_LAMA = 88_888_888L
    const val SUGGESTED_EXPIRY_DELTA_BLOCKS = 144
    const val MAX_TX_BYTES = 256 * 1024
    const val MAX_OFFER_BYTES = 64 * 1024
}
