package maestro.orchestra.util

/**
 * Drops the strings a flow author declared expected before they can fail an assertion.
 *
 * The model is told about them too, but being told is not the same as being bound: the prompt is a
 * hint and this is the guarantee. A screen full of station names and carrier brands needs the
 * guarantee.
 */
object LanguageViolationFilter {

    private val WHITESPACE = Regex("\\s+")

    data class Entry(val raw: String) {
        private val regex: Regex? = raw.trim()
            .takeIf { it.length > 2 && it.startsWith("/") && it.endsWith("/") }
            ?.let { runCatching { Regex(it.substring(1, it.length - 1), RegexOption.IGNORE_CASE) }.getOrNull() }

        private val literal: String = normalize(raw)

        fun matches(text: String): Boolean {
            val candidate = normalize(text)
            // Full match, not containment: `Omio` must not silence `Omio Savings Pass`, which is a
            // phrase that genuinely needs translating.
            return regex?.matches(candidate) ?: candidate.equals(literal, ignoreCase = true)
        }
    }

    data class Filtered<T>(
        val kept: List<T>,
        val suppressed: Int,
    )

    /**
     * Blank entries are dropped rather than treated as a match-everything, so a flow can pass an
     * optional `"${SCREEN_IGNORE}"` that resolves to nothing without silencing the whole screen.
     */
    fun <T> apply(violations: List<T>, ignore: List<String>, text: (T) -> String): Filtered<T> {
        val entries = ignore.filter { it.isNotBlank() }.map(::Entry)
        val kept = violations.filter { violation ->
            val subject = text(violation)
            subject.isNotBlank() && entries.none { it.matches(subject) }
        }
        return Filtered(kept = kept, suppressed = violations.size - kept.size)
    }

    private fun normalize(value: String) = value.replace(WHITESPACE, " ").trim()
}
