package com.mortalive.mortamesh.lama

/**
 * Proposed boundary to the Rust lamacore UniFFI library. No cryptography or transaction serialization is implemented here.
 * Kotlin must never derive keys, sign, calculate sighashes or serialize LAMA transactions.
 */
interface LamaCore {
    val isAvailable: Boolean
    fun versionInfo(): String
    fun createWallet(network: LamaNetwork): NewWallet
    fun restoreWallet(network: LamaNetwork, phrase: String): RestoredWallet
    fun freshReceiveAddress(wallet: WalletHandle): String
    fun buildSend(
        wallet: WalletHandle, utxos: List<LamaUtxo>, toAddress: String, amountLays: Long,
        memo: ByteArray?, expiryHeight: Long?
    ): SendPreview
    fun signAndSerialize(preview: SendPreview): SignedTx
    fun validateForRelay(txHex: String, network: LamaNetwork): RelayCheck
}

class WalletHandle(val id: String)
class NewWallet(val handle: WalletHandle, val phrase24: String, val encryptedBlob: ByteArray)
class RestoredWallet(val handle: WalletHandle, val encryptedBlob: ByteArray)
class LamaUtxo(
    val txid: String, val vout: Int, val address: String, val valueLays: Long,
    val height: Long?, val coinbase: Boolean, val assetId: String?, val assetAmount: Long?
)
class SendPreview(
    val token: String, val network: LamaNetwork, val toAddress: String, val amountLays: Long,
    val feeLays: Long, val changeAddress: String?, val sizeBytes: Int, val memo: ByteArray?,
    val expiryHeight: Long?
)
class SignedTx(val txid: String, val wtxid: String, val hex: String)
class RelayCheck(val ok: Boolean, val txid: String?, val reason: String?)

class LamaCoreMissingException(message: String) : IllegalStateException(message)

/** Until the real Rust library is bundled, every wallet/transaction operation fails loudly. */
object LamaCoreUnavailable : LamaCore {
    private fun missing(): Nothing = throw LamaCoreMissingException(
        "The LAMA Rust core is not bundled in this build. Wallet and transaction operations are disabled."
    )
    override val isAvailable: Boolean = false
    override fun versionInfo(): String = "unavailable"
    override fun createWallet(network: LamaNetwork): NewWallet = missing()
    override fun restoreWallet(network: LamaNetwork, phrase: String): RestoredWallet = missing()
    override fun freshReceiveAddress(wallet: WalletHandle): String = missing()
    override fun buildSend(
        wallet: WalletHandle, utxos: List<LamaUtxo>, toAddress: String, amountLays: Long,
        memo: ByteArray?, expiryHeight: Long?
    ): SendPreview = missing()
    override fun signAndSerialize(preview: SendPreview): SignedTx = missing()
    override fun validateForRelay(txHex: String, network: LamaNetwork): RelayCheck = missing()
}
