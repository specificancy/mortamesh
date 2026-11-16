package com.mortalive.mortamesh.lama

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.LinkedHashMap

/** Values pinned to the current LAMA Rust params.rs. Mainnet deliberately stays unavailable in the app. */
enum class LamaNetwork(val chainId: Int, val addressVersion: Int, val label: String, val testNetwork: Boolean) {
    MAINNET(1, 0x4C, "Mainnet", false),
    TESTNET(2, 0x54, "Testnet", true),
    DEVNET(3, 0x44, "Devnet", true);

    companion object {
        const val MAINNET_ENABLED = false
        const val PREFERENCES_NAME = "lama_wallet_settings_v1"
        const val NETWORK_CHAIN_ID_KEY = "selected_chain_id"
        val DEFAULT = TESTNET
        fun fromChainId(id: Int): LamaNetwork? = entries.firstOrNull { it.chainId == id }
        fun selectable(): List<LamaNetwork> = entries.filter { MAINNET_ENABLED || it.testNetwork }
    }
}

object LamaConstants {
    const val LAYS_PER_LAMA = 100_000_000L
    const val MIN_RELAY_FEE_LAYS = 1_000L
    const val MIN_RELAY_FEE_PER_BYTE = 1L
    const val DUST_LIMIT_LAYS = 1_000L
    const val ASSET_OUT_MIN_VALUE_LAYS = 10_000L
    const val MAX_MEMO_BYTES = 64
    const val MAX_ASSET_PAYLOAD_BYTES = 8 * 1024
    const val MAX_ASSETS_PER_CREATE = 200
    const val MAX_ASSET_OUTPUTS_PER_TX = 200
    const val MAX_ASK_OUTPUTS = 4
    const val MAX_ASSET_NAME_BYTES = 32
    const val MAX_ASSET_TICKER_BYTES = 12
    const val MAX_ASSET_URI_BYTES = 96
    const val MAX_ASSET_DECIMALS = 18
    const val MAX_LAMA_SUPPLY = 88_888_888L
}

/** Bitcoin-alphabet Base58 helper. Rust remains authoritative for wallet and transaction operations. */
internal object LamaBase58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = java.math.BigInteger.valueOf(58)
    private val INDEX = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        if (text.length > 64) return null
        var value = java.math.BigInteger.ZERO
        for (char in text) {
            val code = char.code
            val digit = if (code < INDEX.size) INDEX[code] else -1
            if (digit < 0) return null
            value = value.multiply(BASE).add(java.math.BigInteger.valueOf(digit.toLong()))
        }
        var leadingOnes = 0
        while (leadingOnes < text.length && text[leadingOnes] == '1') leadingOnes++
        var body = value.toByteArray()
        if (body.isNotEmpty() && body[0].toInt() == 0) body = body.copyOfRange(1, body.size)
        return ByteArray(leadingOnes) + body
    }
}

object LamaAddress {
    const val SCHEME_ML_DSA_44 = 0x04
    private const val DECODED_LENGTH = 38
    enum class Problem { EMPTY, BAD_CHARACTERS, BAD_LENGTH, BAD_CHECKSUM, UNSUPPORTED_SCHEME, WRONG_NETWORK }

    /** Checks Base58, checksum, active ML-DSA-44 scheme and selected network version only. */
    fun validate(address: String, expected: LamaNetwork): Problem? {
        val text = address.trim()
        if (text.isEmpty()) return Problem.EMPTY
        if (text.length > 64) return Problem.BAD_LENGTH
        val bytes = LamaBase58.decode(text) ?: return Problem.BAD_CHARACTERS
        if (bytes.size != DECODED_LENGTH) return Problem.BAD_LENGTH
        val checksum = doubleSha256(bytes.copyOfRange(0, 34))
        if ((0..3).any { checksum[it] != bytes[34 + it] }) return Problem.BAD_CHECKSUM
        if ((bytes[1].toInt() and 0xff) != SCHEME_ML_DSA_44) return Problem.UNSUPPORTED_SCHEME
        if ((bytes[0].toInt() and 0xff) != expected.addressVersion) return Problem.WRONG_NETWORK
        return null
    }

    fun networkOf(address: String): LamaNetwork? {
        val text = address.trim()
        if (text.isEmpty() || text.length > 64) return null
        val bytes = LamaBase58.decode(text) ?: return null
        if (bytes.size != DECODED_LENGTH) return null
        val checksum = doubleSha256(bytes.copyOfRange(0, 34))
        if ((0..3).any { checksum[it] != bytes[34 + it] }) return null
        if ((bytes[1].toInt() and 0xff) != SCHEME_ML_DSA_44) return null
        return LamaNetwork.entries.firstOrNull { it.addressVersion == (bytes[0].toInt() and 0xff) }
    }

    fun groupForDisplay(address: String, size: Int = 4): String {
        require(size > 0)
        return address.trim().chunked(size).joinToString(" ")
    }

    fun message(problem: Problem, expected: LamaNetwork): String = when (problem) {
        Problem.EMPTY -> "Enter a LAMA address."
        Problem.BAD_CHARACTERS -> "The address contains characters that are not valid in LAMA Base58."
        Problem.BAD_LENGTH -> "The decoded address does not have the expected 38-byte format."
        Problem.BAD_CHECKSUM -> "The address checksum is invalid. Check for a mistyped character."
        Problem.UNSUPPORTED_SCHEME -> "This address does not use the active ML-DSA-44 signature scheme."
        Problem.WRONG_NETWORK -> "This address belongs to another network. The selected network is " + expected.label + "."
    }

    private fun doubleSha256(bytes: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        return sha.digest(sha.digest(bytes))
    }
}

/** LAMA balances and payments use integer lays; floating-point values are deliberately not used. */
object LamaAmount {
    private val PLAIN_DECIMAL = Regex("^\\d+(\\.\\d{1,8})?$")
    private val MAX_SUPPLY_LAYS = LamaConstants.MAX_LAMA_SUPPLY * LamaConstants.LAYS_PER_LAMA

    fun format(lays: Long): String {
        require(lays >= 0) { "Lays cannot be negative." }
        val whole = lays / LamaConstants.LAYS_PER_LAMA
        val fraction = lays % LamaConstants.LAYS_PER_LAMA
        if (fraction == 0L) return whole.toString()
        return whole.toString() + "." + fraction.toString().padStart(8, '0').trimEnd('0')
    }

    fun parse(text: String): Long? {
        val normalized = text.trim()
        if (!PLAIN_DECIMAL.matches(normalized)) return null
        return try {
            BigDecimal(normalized).movePointRight(8).longValueExact()
                .takeIf { it in 0..MAX_SUPPLY_LAYS }
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun parsePositive(text: String): Long? = parse(text)?.takeIf { it > 0L }
    fun isDust(lays: Long): Boolean = lays < LamaConstants.DUST_LIMIT_LAYS
    fun minimumRelayFee(sizeBytes: Int): Long {
        require(sizeBytes >= 0)
        return maxOf(LamaConstants.MIN_RELAY_FEE_LAYS, sizeBytes.toLong() * LamaConstants.MIN_RELAY_FEE_PER_BYTE)
    }
}

object LamaAssetMetadataVerifier {
    enum class Result { MATCH, HASH_MISMATCH, INVALID_EXPECTED_HASH, TOO_LARGE }

    fun verify(bytes: ByteArray, expectedHashHex: String, maxBytes: Int): Result {
        if (!PayHex.isHash256(expectedHashHex)) return Result.INVALID_EXPECTED_HASH
        if (maxBytes < 0 || bytes.size > maxBytes) return Result.TOO_LARGE
        val actual = PayHex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
        return if (actual == expectedHashHex) Result.MATCH else Result.HASH_MISMATCH
    }

    fun sha256Hex(bytes: ByteArray): String =
        PayHex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
}

object PayHex {
    fun decode(hex: String, maxBytes: Int): ByteArray? {
        if (maxBytes < 1 || hex.isEmpty() || hex.length % 2 != 0 || hex.length / 2 > maxBytes) return null
        val output = ByteArray(hex.length / 2)
        for (index in output.indices) {
            val hi = digit(hex[index * 2])
            val lo = digit(hex[index * 2 + 1])
            if (hi < 0 || lo < 0) return null
            output[index] = ((hi shl 4) or lo).toByte()
        }
        return output
    }

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val alphabet = "0123456789abcdef"
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }

    fun isHash256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun digit(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        else -> -1
    }
}

/**
 * Contract for the future UniFFI/JNI wrapper around the Rust lamacore crate.
 * No key derivation, signing, consensus serialization or PoW verification is implemented in Kotlin.
 */
interface LamaCore {
    val isAvailable: Boolean
    fun createWallet(network: LamaNetwork): NewWallet
    fun restoreWallet(network: LamaNetwork, phrase24: String): RestoredWallet
    fun freshReceiveAddress(wallet: WalletHandle): String
    fun buildSend(wallet: WalletHandle, toAddress: String, amountLays: Long, memo: ByteArray?): SendPreview
    fun signAndSerialize(preview: SendPreview): SignedTransaction
    fun verifyHeaders(previous: BlockHeader?, headers: List<BlockHeader>): HeaderVerification
    fun verifyMerkle(leafHex: String, branch: List<MerkleStep>, rootHex: String): Boolean
    fun validateForRelay(txHex: String, network: LamaNetwork): RelayValidation
}

class WalletHandle(val id: String)
class NewWallet(val handle: WalletHandle, val phrase24: String)
class RestoredWallet(val handle: WalletHandle)
class SendPreview(
    val network: LamaNetwork,
    val toAddress: String,
    val amountLays: Long,
    val feeLays: Long,
    val sizeBytes: Int,
    val changeAddress: String?
)
class SignedTransaction(val txid: String, val wtxid: String, val hex: String)
class BlockHeader(val height: Long, val hash: String, val previousHash: String)
class MerkleStep(val hash: String, val siblingOnRight: Boolean)
class HeaderVerification(val ok: Boolean, val height: Long?, val reason: String?)
class RelayValidation(val ok: Boolean, val txid: String?, val reason: String?)

class LamaCoreUnavailableException : IllegalStateException(
    "LAMA wallet operations are disabled until the Rust lamacore library and Android bindings are integrated."
)

/** Explicit fail-closed implementation used until the real Rust library is bundled. */
object MissingLamaCore : LamaCore {
    override val isAvailable = false
    private fun missing(): Nothing = throw LamaCoreUnavailableException()
    override fun createWallet(network: LamaNetwork): NewWallet = missing()
    override fun restoreWallet(network: LamaNetwork, phrase24: String): RestoredWallet = missing()
    override fun freshReceiveAddress(wallet: WalletHandle): String = missing()
    override fun buildSend(wallet: WalletHandle, toAddress: String, amountLays: Long, memo: ByteArray?): SendPreview = missing()
    override fun signAndSerialize(preview: SendPreview): SignedTransaction = missing()
    override fun verifyHeaders(previous: BlockHeader?, headers: List<BlockHeader>): HeaderVerification = missing()
    override fun verifyMerkle(leafHex: String, branch: List<MerkleStep>, rootHex: String): Boolean = missing()
    override fun validateForRelay(txHex: String, network: LamaNetwork): RelayValidation = missing()
}

/** App-level guardrail; Rust remains the source of truth for building/signing/validation. */
class LamaRepository(
    private val core: LamaCore,
    private val selectedNetwork: () -> LamaNetwork
) {
    fun validateAddress(address: String): LamaAddress.Problem? =
        LamaAddress.validate(address, usableNetwork())

    fun parseAmount(text: String): Long? = LamaAmount.parsePositive(text)

    fun createWallet(): NewWallet {
        requireCore()
        val result = core.createWallet(usableNetwork())
        check(result.phrase24.trim().split(Regex("\\s+")).size == 24) {
            "The Rust core did not return a 24-word recovery phrase."
        }
        return result
    }

    fun restoreWallet(phrase24: String): RestoredWallet {
        requireCore()
        require(phrase24.trim().split(Regex("\\s+")).size == 24) {
            "A LAMA recovery phrase must contain exactly 24 words."
        }
        return core.restoreWallet(usableNetwork(), phrase24)
    }

    fun freshReceiveAddress(wallet: WalletHandle): String {
        requireCore()
        val address = core.freshReceiveAddress(wallet)
        check(LamaAddress.validate(address, usableNetwork()) == null) {
            "Rust core returned an address for the wrong network or an invalid address."
        }
        return address
    }

    fun buildSend(wallet: WalletHandle, recipient: String, amountText: String, memo: ByteArray? = null): SendPreview {
        requireCore()
        val network = usableNetwork()
        val problem = LamaAddress.validate(recipient, network)
        require(problem == null) {
            LamaAddress.message(problem ?: throw IllegalStateException("Address validation failed."), network)
        }
        val amount = LamaAmount.parsePositive(amountText)
            ?: throw IllegalArgumentException("Enter a positive amount with no more than 8 decimal places.")
        require(!LamaAmount.isDust(amount)) { "Amount is below the LAMA dust limit." }
        require(memo == null || memo.size <= LamaConstants.MAX_MEMO_BYTES) { "Payment memo exceeds 64 bytes." }

        val preview = core.buildSend(wallet, recipient.trim(), amount, memo?.copyOf())
        check(preview.network == network) { "Rust core preview uses a different network." }
        check(preview.toAddress == recipient.trim() && preview.amountLays == amount) {
            "Rust core preview does not match the recipient or amount."
        }
        check(preview.sizeBytes > 0 && preview.feeLays >= LamaAmount.minimumRelayFee(preview.sizeBytes)) {
            "Rust core preview has an invalid size or insufficient relay fee."
        }
        check(preview.changeAddress == null || LamaAddress.validate(preview.changeAddress, network) == null) {
            "Rust core returned a change address for another network."
        }
        return preview
    }

    /** This gate is for a later biometric/PIN confirmation screen; it is not a UI authentication mechanism itself. */
    fun signAfterExplicitConfirmation(preview: SendPreview, confirmed: Boolean): SignedTransaction {
        requireCore()
        require(confirmed) { "Signing requires explicit user confirmation." }
        require(preview.network == usableNetwork()) { "The selected network changed after the preview." }
        val signed = core.signAndSerialize(preview)
        require(PayHex.isHash256(signed.txid) && PayHex.isHash256(signed.wtxid)) {
            "Rust core returned invalid transaction identifiers."
        }
        require(signed.hex.isNotEmpty() && signed.hex.length % 2 == 0) {
            "Rust core returned malformed transaction hex."
        }
        return signed
    }

    fun verifyHeaders(previous: BlockHeader?, headers: List<BlockHeader>): HeaderVerification {
        requireCore()
        require(headers.size <= 500) { "A light-client batch cannot exceed 500 headers." }
        return core.verifyHeaders(previous, headers)
    }

    fun verifyMerkle(leafHex: String, branch: List<MerkleStep>, rootHex: String): Boolean {
        requireCore()
        require(PayHex.isHash256(leafHex) && PayHex.isHash256(rootHex))
        require(branch.all { PayHex.isHash256(it.hash) })
        return core.verifyMerkle(leafHex, branch, rootHex)
    }

    private fun usableNetwork(): LamaNetwork {
        val network = selectedNetwork()
        check(network in LamaNetwork.selectable()) { "LAMA Mainnet is disabled in this pre-release wallet." }
        return network
    }

    private fun requireCore() {
        check(core.isAvailable) { "LAMA wallet is unavailable until Rust lamacore is integrated." }
    }
}

/** Bounded in-memory queue. A real Rust validator must approve every tx before it can be stored. */
class PayTxStore(
    private val maxEntries: Int,
    private val maxBytes: Int,
    private val maxTxBytes: Int,
    private val ttlMillis: Long,
    private val validate: (String, LamaNetwork) -> RelayValidation,
    private val now: () -> Long = System::currentTimeMillis
) {
    enum class OfferResult { STORED, DUPLICATE, INVALID, CAPACITY }
    data class Entry(val txid: String, val hex: String, val storedAt: Long)
    private val entries = LinkedHashMap<String, Entry>()
    private var byteCount = 0

    init {
        require(maxEntries > 0 && maxBytes > 0 && maxTxBytes > 0 && ttlMillis > 0)
    }

    @Synchronized fun offer(hex: String, network: LamaNetwork): OfferResult {
        prune()
        if (PayHex.decode(hex, maxTxBytes) == null) return OfferResult.INVALID
        val result = try { validate(hex, network) } catch (_: Exception) { return OfferResult.INVALID }
        val txid = result.txid
        if (!result.ok || txid == null || !PayHex.isHash256(txid)) return OfferResult.INVALID
        if (entries.containsKey(txid)) return OfferResult.DUPLICATE
        val bytes = hex.length / 2
        if (entries.size >= maxEntries || bytes > maxBytes || byteCount + bytes > maxBytes) return OfferResult.CAPACITY
        entries[txid] = Entry(txid, hex, now())
        byteCount += bytes
        return OfferResult.STORED
    }

    @Synchronized fun pending(): List<Entry> { prune(); return entries.values.toList() }
    @Synchronized fun size(): Int = entries.size
    @Synchronized fun storedBytes(): Int = byteCount

    @Synchronized fun prune() {
        val cutoff = now() - ttlMillis
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.storedAt <= cutoff) {
                byteCount -= entry.hex.length / 2
                iterator.remove()
            }
        }
    }
}
