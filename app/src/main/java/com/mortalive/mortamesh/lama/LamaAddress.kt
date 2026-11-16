package com.mortalive.mortamesh.lama

import java.security.MessageDigest

/**
 * Structural pre-check: Base58(version | scheme=0x04 | 32-byte hash | 4-byte double-SHA256 checksum).
 * It catches malformed and wrong-network addresses, but does not prove ownership, balance or UTXO status.
 * Rust lamacore remains the authority for wallet operations and transactions.
 */
object LamaAddress {
    const val SCHEME_ML_DSA_44 = 0x04
    private const val DECODED_LENGTH = 38
    enum class Problem { EMPTY, BAD_CHARACTERS, BAD_LENGTH, BAD_CHECKSUM, UNSUPPORTED_SCHEME, WRONG_NETWORK }

    fun validate(address: String, expected: LamaNetwork): Problem? {
        val text = address.trim()
        if (text.isEmpty()) return Problem.EMPTY
        if (text.length > 64) return Problem.BAD_LENGTH
        val raw = Base58.decode(text) ?: return Problem.BAD_CHARACTERS
        if (raw.size != DECODED_LENGTH) return Problem.BAD_LENGTH
        val sha = MessageDigest.getInstance("SHA-256")
        val checksum = sha.digest(sha.digest(raw.copyOfRange(0, 34)))
        for (i in 0 until 4) if (checksum[i] != raw[34 + i]) return Problem.BAD_CHECKSUM
        if ((raw[1].toInt() and 0xFF) != SCHEME_ML_DSA_44) return Problem.UNSUPPORTED_SCHEME
        if ((raw[0].toInt() and 0xFF) != expected.netVersion) return Problem.WRONG_NETWORK
        return null
    }

    fun networkOf(address: String): LamaNetwork? {
        val text = address.trim()
        if (text.isEmpty() || text.length > 64) return null
        val raw = Base58.decode(text) ?: return null
        if (raw.size != DECODED_LENGTH) return null
        val sha = MessageDigest.getInstance("SHA-256")
        val checksum = sha.digest(sha.digest(raw.copyOfRange(0, 34)))
        if ((0 until 4).any { checksum[it] != raw[34 + it] }) return null
        if ((raw[1].toInt() and 0xFF) != SCHEME_ML_DSA_44) return null
        return LamaNetwork.values().firstOrNull { it.netVersion == (raw[0].toInt() and 0xFF) }
    }

    fun groupForDisplay(address: String, groupSize: Int = 4): String {
        require(groupSize > 0) { "groupSize must be positive" }
        return address.trim().chunked(groupSize).joinToString(" ")
    }

    fun message(problem: Problem, expected: LamaNetwork): String = userMessage(problem, expected)
    fun userMessage(problem: Problem, expected: LamaNetwork): String = when (problem) {
        Problem.EMPTY -> "Enter a LAMA address."
        Problem.BAD_CHARACTERS -> "This address contains characters that LAMA Base58 addresses do not use."
        Problem.BAD_LENGTH -> "This address has the wrong decoded length."
        Problem.BAD_CHECKSUM -> "Address checksum failed. Check for a mistyped character."
        Problem.UNSUPPORTED_SCHEME -> "This address uses a signature scheme this app does not support."
        Problem.WRONG_NETWORK -> "This address belongs to a different network. The selected network is \${expected.label}."
    }
}
