package com.mortalive.mortamesh

/** Rules shared by the World Chat composer, protocol receiver and JVM tests. */
internal object WorldChatPolicy {
    const val MAX_CHARACTERS = 500

    fun codePointCount(value: String): Int = value.codePointCount(0, value.length)

    fun isValidPostText(value: String): Boolean {
        if (value.isBlank() || codePointCount(value) !in 1..MAX_CHARACTERS) return false
        if (value.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return false
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(current) -> return false
                else -> index++
            }
        }
        return true
    }
}
