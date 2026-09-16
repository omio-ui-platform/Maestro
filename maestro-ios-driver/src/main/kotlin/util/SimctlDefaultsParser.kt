package util

/**
 * Reads back what `xcrun simctl spawn <id> defaults read <domain> <key>` prints.
 *
 * `defaults` has no machine-readable output mode, so this parses its old-style plist rendering: a
 * scalar comes back bare on one line, an array as parenthesised lines with optional quoting and a
 * trailing comma on all but the last entry.
 */
object SimctlDefaultsParser {

    /** `de_DE` -> `de_DE`; an error message or empty output -> null. */
    fun parseScalar(raw: String?): String? {
        val value = raw?.trim()?.trimQuotes() ?: return null
        if (value.isEmpty() || value.looksLikeError()) return null
        return value
    }

    /**
     * ```
     * (
     *     de,
     *     "en-GB"
     * )
     * ```
     * -> `["de", "en-GB"]`. Also accepts the single-line `(de)` form `defaults` prints for one entry.
     */
    fun parseArray(raw: String?): List<String> {
        val body = raw?.trim() ?: return emptyList()
        if (body.isEmpty() || body.looksLikeError()) return emptyList()
        if (!body.startsWith("(") || !body.endsWith(")")) return emptyList()

        return body.removePrefix("(").removeSuffix(")")
            .split(",")
            .map { it.trim().trimQuotes() }
            .filter { it.isNotEmpty() }
    }

    private fun String.trimQuotes() = removeSurrounding("\"").trim()

    /**
     * `defaults` exits non-zero and prints a message when a key is unset. Callers merge stderr into
     * stdout to keep one stream, so the message arrives here rather than being thrown.
     */
    private fun String.looksLikeError() =
        contains("does not exist", ignoreCase = true) || startsWith("20") && contains("defaults[")
}
