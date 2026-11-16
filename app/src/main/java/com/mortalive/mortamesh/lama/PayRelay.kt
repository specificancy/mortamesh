package com.mortalive.mortamesh.lama

import java.security.SecureRandom
import java.util.LinkedHashMap

/** Strict lower-case hex parsing for signed transaction data received from an untrusted peer. */
object PayHex {
    fun decode(hex: String, maxBytes: Int): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0 || hex.length / 2 > maxBytes) return null
        val output = ByteArray(hex.length / 2)
        for (index in output.indices) {
            val high = digit(hex[index * 2])
            val low = digit(hex[index * 2 + 1])
            if (high < 0 || low < 0) return null
            output[index] = ((high shl 4) or low).toByte()
        }
        return output
    }

    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val alphabet = "0123456789abcdef"
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0F])
        }
    }

    fun isTxid(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun digit(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        else -> -1
    }
}

/** Provisional LAMA-PAY-v1 memo helper. It is not connected to a live payment flow. */
object LamaPayMemo {
    const val VERSION = 0x01
    const val LENGTH = 32
    fun generate(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(LENGTH).also {
        random.nextBytes(it)
        it[0] = VERSION.toByte()
    }
    fun isValid(memo: ByteArray): Boolean = memo.size == LENGTH && (memo[0].toInt() and 0xFF) == VERSION
}

/**
 * Bounded in-memory carry queue for already-signed transactions. It is not connected to a transport, gateway or
 * broadcast service. Each entry requires an injected Rust-core validation result; capacity never evicts older items.
 */
class PayTxStore(
    private val maxEntries: Int,
    private val maxTotalBytes: Int,
    private val ttlMs: Long,
    private val validateWithCore: (txHex: String, network: LamaNetwork) -> RelayCheck,
    private val now: () -> Long = System::currentTimeMillis
) {
    enum class Result { STORED, DUPLICATE, REJECTED_INVALID, REJECTED_CAPACITY }
    class Entry(
        val txid: String,
        val hex: String,
        val network: LamaNetwork,
        val storedAt: Long,
        var broadcastAt: Long? = null
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var totalBytes = 0

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        require(ttlMs > 0L) { "ttlMs must be positive" }
    }

    @Synchronized
    fun offer(hex: String, network: LamaNetwork): Result {
        prune()
        if (PayHex.decode(hex, LamaConstants.MAX_TX_BYTES) == null) return Result.REJECTED_INVALID
        val checked = try { validateWithCore(hex, network) } catch (_: Exception) { return Result.REJECTED_INVALID }
        val txid = checked.txid
        if (!checked.ok || txid == null || !PayHex.isTxid(txid)) return Result.REJECTED_INVALID
        if (entries.containsKey(txid)) return Result.DUPLICATE
        val bytes = hex.length / 2
        if (bytes > maxTotalBytes || entries.size >= maxEntries || totalBytes + bytes > maxTotalBytes) {
            return Result.REJECTED_CAPACITY
        }
        entries[txid] = Entry(txid, hex, network, now())
        totalBytes += bytes
        return Result.STORED
    }

    @Synchronized
    fun pendingForBroadcast(network: LamaNetwork): List<Entry> {
        prune()
        return entries.values.filter { it.network == network && it.broadcastAt == null }
    }

    /** Gateway acceptance is not a block confirmation. */
    @Synchronized fun markBroadcast(txid: String) { entries[txid]?.broadcastAt = now() }
    @Synchronized fun size(): Int = entries.size
    @Synchronized fun bytesStored(): Int = totalBytes
    @Synchronized fun contains(txid: String): Boolean = entries.containsKey(txid)

    @Synchronized
    fun prune() {
        val cutoff = now() - ttlMs
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.storedAt <= cutoff) {
                totalBytes -= entry.hex.length / 2
                iterator.remove()
            }
        }
    }
}
