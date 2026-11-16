package com.mortalive.mortamesh.lama

import java.math.BigInteger

/** Bitcoin-alphabet Base58. This is a UI parsing helper; Rust remains authoritative. */
internal object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)
    private val INDEX = IntArray(128) { -1 }.also { table ->
        for (i in ALPHABET.indices) table[ALPHABET[i].code] = i
    }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        var value = BigInteger(1, input)
        val encoded = StringBuilder()
        while (value.signum() > 0) {
            val qr = value.divideAndRemainder(BASE)
            encoded.append(ALPHABET[qr[1].toInt()])
            value = qr[0]
        }
        repeat(zeros) { encoded.append('1') }
        return encoded.reverse().toString()
    }

    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        if (text.length > 64) return null
        var value = BigInteger.ZERO
        for (char in text) {
            val code = char.code
            val digit = if (code < INDEX.size) INDEX[code] else -1
            if (digit < 0) return null
            value = value.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }
        var leadingOnes = 0
        while (leadingOnes < text.length && text[leadingOnes] == '1') leadingOnes++
        var body = value.toByteArray()
        if (body.isNotEmpty() && body[0].toInt() == 0) body = body.copyOfRange(1, body.size)
        return ByteArray(leadingOnes) + body
    }
}
