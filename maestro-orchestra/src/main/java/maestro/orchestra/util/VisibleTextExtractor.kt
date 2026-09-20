package maestro.orchestra.util

import maestro.TreeNode
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.ViewHierarchy

/**
 * Collects the user-visible strings on a screen, for handing to a model alongside a screenshot.
 *
 * Deliberately not [ViewHierarchySerializer]: that one is shaped for element location, so every line
 * carries `rid=`, `bounds` and `[clickable]`. Those are developer identifiers, almost always English,
 * and a language check asked to judge them reports the app's own resource ids as translation gaps.
 * This emits nothing but the text a user can read.
 */
object VisibleTextExtractor {

    /**
     * Every attribute that can hold user-facing text, across both platforms: Android fills
     * `text`, `accessibilityText` (from content-desc), `hintText` and `error`; iOS fills
     * `accessibilityText` (label), `title`, `value` and `hintText` (placeholder).
     */
    val TEXT_ATTRIBUTES = listOf("text", "accessibilityText", "hintText", "value", "title", "error")

    const val DEFAULT_MAX_STRINGS = 200

    /** Strings made only of digits, punctuation, symbols and space carry no language. */
    private val NON_LINGUISTIC = Regex("^[\\p{N}\\p{P}\\p{S}\\s]+$")
    private val WHITESPACE = Regex("\\s+")

    /**
     * A testID that has surfaced as readable text rather than as an id.
     *
     * Excluding `rid=` from this output is not enough on its own. React Native maps a component's
     * `testID` onto the iOS accessibility label, so a developer identifier arrives in
     * `accessibilityText` -- the very attribute a user-facing label also arrives in. It is the same
     * mechanism a flow relies on when it taps `passwordless-signin-button` as TEXT, so it cannot be
     * turned off; it has to be filtered here.
     *
     * Seen in a real run: a my-bookings segmented control sets `testID={tab.name}`, and the model
     * dutifully reported `tab-upcoming` and `tab-archived` as untranslated English. Every tab bar
     * and segmented control in the app would do the same, in all 33 locales.
     *
     * Applied PER TOKEN, not to the whole string, because that is how these actually arrive. iOS
     * rolls a subtree's accessibility labels into one aggregated label on the container, so a real
     * my-bookings screen yielded:
     *
     *     "Meine Buchungen Meine Buchungen tab-upcoming tab-archived Vertikaler Rollbalken, ..."
     *
     * -- two testIDs embedded in a paragraph of otherwise correct German. Testing the whole string
     * leaves them in; testing each word removes them and keeps the sentence.
     *
     * Matches a lowercase run joined by `-`, `_` or `.`: `tab-upcoming`, `btn_accept_all`,
     * `ic_tabbar_profile`. Requiring all-lower-case is what protects display copy, which is
     * capitalised -- German compounds like "Live-Updates" are kept. The residual risk is a genuinely
     * lowercase hyphenated word in UI copy, which is rare enough to accept and would at worst hide
     * one word from the check rather than invent a finding.
     *
     * Cross-referencing the tree's `resource-id`s instead would be more precise in principle but
     * does not work here: those tabs carry ids `upcoming`/`archived` while their labels read
     * `tab-upcoming`/`tab-archived`, so the id never matches the leaked text.
     */
    private val LOOKS_LIKE_IDENTIFIER = Regex("^[a-z0-9]+([._-][a-z0-9]+)+$")

    data class Result(
        val strings: List<String>,
        val omitted: Int,
    ) {
        /** Null when the screen yielded no text at all, so callers can omit the section entirely. */
        fun render(): String? {
            if (strings.isEmpty()) return null
            return buildString {
                strings.forEach { appendLine("- $it") }
                // Said out loud rather than truncating in silence: a model told this list is
                // complete would otherwise vouch for text it was never shown.
                if (omitted > 0) append("... and $omitted more not listed")
            }.trimEnd()
        }
    }

    fun extract(hierarchy: ViewHierarchy, maxStrings: Int = DEFAULT_MAX_STRINGS): Result =
        extract(hierarchy.aggregate(), maxStrings)

    fun extract(nodes: List<TreeNode>, maxStrings: Int = DEFAULT_MAX_STRINGS): Result {
        // Insertion-ordered so the list reads top-to-bottom like the screen does. The same string
        // arrives repeatedly -- iOS reports one label as `text`, `title` and `accessibilityText` --
        // and a model shown it three times tends to report it three times.
        val seen = LinkedHashSet<String>()
        var omitted = 0

        for (node in nodes) {
            if (!isRendered(node)) continue
            for (attribute in TEXT_ATTRIBUTES) {
                val cleaned = clean(node.attributes[attribute]) ?: continue
                if (cleaned in seen) continue
                if (seen.size >= maxStrings) omitted++ else seen.add(cleaned)
            }
        }

        return Result(strings = seen.toList(), omitted = omitted)
    }

    private fun clean(raw: String?): String? {
        val collapsed = raw?.replace(WHITESPACE, " ")?.trim() ?: return null
        val scrubbed = collapsed
            .split(' ')
            .filterNot { LOOKS_LIKE_IDENTIFIER.matches(it) }
            .joinToString(" ")
            .trim()
        if (scrubbed.isEmpty() || NON_LINGUISTIC.matches(scrubbed)) return null
        return scrubbed
    }

    /**
     * A node with no bounds was never laid out, and one with no area occupies no pixels. The tree
     * itself is already viewport-filtered by [ViewHierarchy.from], so this is the remaining case.
     */
    private fun isRendered(node: TreeNode): Boolean {
        val bounds = runCatching { node.toUiElementOrNull()?.bounds }.getOrNull() ?: return false
        return bounds.width > 0 && bounds.height > 0
    }
}
