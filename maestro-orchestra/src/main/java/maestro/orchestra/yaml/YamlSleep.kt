package maestro.orchestra.yaml

/**
 * YAML for the `sleep` command:
 *
 *   - sleep:
 *       seconds: 2
 *
 * `seconds` is optional — omit it (or leave it empty) for the 4-second default.
 */
data class YamlSleep(
    val seconds: Double? = null,
    val label: String? = null,
    val optional: Boolean = false,
)
