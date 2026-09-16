package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlAssertLanguageWithAI(
    val language: String,
    val ignore: List<String> = emptyList(),
    val optional: Boolean = false,
    val label: String? = null,
) {

    companion object {

        /** Supports the shorthand `- assertLanguageWithAI: German`. */
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(language: String): YamlAssertLanguageWithAI {
            return YamlAssertLanguageWithAI(
                language = language,
            )
        }
    }
}
