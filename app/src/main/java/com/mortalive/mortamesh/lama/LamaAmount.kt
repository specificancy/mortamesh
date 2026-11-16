package com.mortalive.mortamesh.lama

import java.math.BigDecimal

/** Amounts are integer lays. Floating-point arithmetic is not used for balances or payments. */
object LamaAmount {
    private val DECIMAL = Regex("""^\d+(\.\d{1,8})?$""")
    private const val MAX_SUPPLY_LAYS = LamaConstants.MAX_SUPPLY_LAMA * LamaConstants.LAYS_PER_LAMA

    fun format(lays: Long): String {
        require(lays >= 0) { "negative amount" }
        val whole = lays / LamaConstants.LAYS_PER_LAMA
        val fraction = lays % LamaConstants.LAYS_PER_LAMA
        if (fraction == 0L) return whole.toString()
        return whole.toString() + "." + fraction.toString().padStart(8, '0').trimEnd('0')
    }

    fun parse(text: String): Long? {
        val value = text.trim()
        if (!DECIMAL.matches(value)) return null
        return try {
            val lays = BigDecimal(value).movePointRight(8).longValueExact()
            lays.takeIf { it in 0..MAX_SUPPLY_LAYS }
        } catch (_: ArithmeticException) {
            null
        }
    }

    fun parsePositive(text: String): Long? = parse(text)?.takeIf { it > 0L }
    fun isDust(lays: Long): Boolean = lays < LamaConstants.DUST_LIMIT_LAYS

    /** Lower relay fee bound. Final size/fee calculation must come from Rust lamacore. */
    fun feeFloorForSize(sizeBytes: Int): Long {
        require(sizeBytes >= 0) { "negative transaction size" }
        return maxOf(LamaConstants.MIN_RELAY_FEE_LAYS, sizeBytes.toLong())
    }
}
