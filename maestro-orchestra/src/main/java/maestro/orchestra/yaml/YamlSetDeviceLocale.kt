package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator

data class YamlSetDeviceLocale(
    val locale: String,
    val optional: Boolean = false,
    val label: String? = null,
) {

    companion object {

        /** Supports the shorthand `- setDeviceLocale: de-DE`. */
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(locale: String): YamlSetDeviceLocale {
            return YamlSetDeviceLocale(locale = locale)
        }
    }
}
