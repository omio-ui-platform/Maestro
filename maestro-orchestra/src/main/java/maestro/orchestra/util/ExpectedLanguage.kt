package maestro.orchestra.util

import java.util.Locale

/**
 * A language an assertion expects a screen to be written in, resolved from whatever the flow author
 * wrote: an ISO 639-1 code (`de`), a code with a region in either separator (`de_DE`, `pt-BR`),
 * a script-qualified tag (`zh-Hans`), or the English name of the language (`German`).
 *
 * The [displayName] is what reaches the model; a name reads far better in a prompt than a code, and
 * pinning the [tag] alongside it disambiguates the pairs a name alone cannot (`en-GB` vs `en-US`,
 * `pt-BR` vs `pt-PT`).
 *
 * [languageName] is the same name without the parenthesised region, for the one-line failure
 * message -- see [describeBriefly].
 */
data class ExpectedLanguage(
    val tag: String,
    val displayName: String,
    val languageName: String = displayName,
) {

    /** e.g. `German (Germany) [de-DE]`, or `German [de]` when no region was given. */
    fun describe(): String = "$displayName [$tag]"

    /**
     * e.g. `German [de-DE]` -- deliberately free of parentheses, unlike [describe].
     *
     * A failed flow's message is printed inline as ` (<message>)` on the result line
     * (`TestSuiteStatusView.showFlowCompletion`), and the pipeline greps that line to build the
     * Slack row. Its capture group is `(\s+\(.+\))?`, and `.` does not match a newline, so a
     * parenthesis inside the message truncates the captured text at the wrong place and a second
     * line is dropped entirely. Keeping this short, single-line and paren-free is what puts the
     * offending strings on the Slack row instead of a mangled fragment.
     */
    fun describeBriefly(): String = "$languageName [$tag]"

    companion object {

        /**
         * Resolves [raw] to a language, or null when it names no language Java knows.
         *
         * Only the language subtag is validated. Region and script are passed through as written,
         * because the useful set is wider than any list held here: iOS wants `es-419` (a UN M.49
         * area, not an ISO country) and `zh-Hans` (a script), and the device itself is the authority
         * on what it will accept.
         */
        fun parse(raw: String): ExpectedLanguage? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            return fromTag(trimmed) ?: fromEnglishName(trimmed)
        }

        private fun fromTag(raw: String): ExpectedLanguage? {
            val locale = Locale.forLanguageTag(raw.replace('_', '-'))
            val language = locale.language
            if (language.isEmpty() || !isKnownLanguage(language)) return null

            val name = locale.getDisplayLanguage(Locale.ENGLISH)
            val region = locale.getDisplayCountry(Locale.ENGLISH)
            val displayName = if (region.isEmpty()) name else "$name ($region)"
            return ExpectedLanguage(
                tag = locale.toLanguageTag(),
                displayName = displayName,
                languageName = name,
            )
        }

        private fun fromEnglishName(raw: String): ExpectedLanguage? {
            val code = byEnglishName[raw.lowercase(Locale.ENGLISH)] ?: return null
            val locale = Locale.forLanguageTag(code)
            return ExpectedLanguage(
                tag = locale.toLanguageTag(),
                displayName = locale.getDisplayLanguage(Locale.ENGLISH),
            )
        }

        /**
         * Java answers this two ways and both are needed: [Locale.getISOLanguages] still returns the
         * legacy spellings for a few languages (`in` for Indonesian, `iw` for Hebrew), while
         * [Locale.forLanguageTag] may hand back either spelling depending on the JVM's
         * `java.locale.useOldISOCodes` setting. A code Java does not know renders as itself, so a
         * display name that differs from the code is the reliable second signal.
         */
        private fun isKnownLanguage(language: String): Boolean {
            if (language in isoLanguages) return true
            val display = Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH)
            return display.isNotEmpty() && !display.equals(language, ignoreCase = true)
        }

        private val isoLanguages: Set<String> by lazy { Locale.getISOLanguages().toSet() }

        private val byEnglishName: Map<String, String> by lazy {
            Locale.getISOLanguages().associate { code ->
                Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ENGLISH) to code
            }
        }
    }
}
