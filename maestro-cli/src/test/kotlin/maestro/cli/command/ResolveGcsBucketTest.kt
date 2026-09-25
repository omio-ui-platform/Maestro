package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ResolveGcsBucketTest {

    @Test
    fun `-e GCS_BUCKET wins, like every other recording input`() {
        assertThat(resolveGcsBucket(mapOf("GCS_BUCKET" to "from-e"), "from-cli-or-process-env")).isEqualTo("from-e")
    }

    @Test
    fun `falls back to --gcs-bucket or the process env when -e does not set it`() {
        assertThat(resolveGcsBucket(emptyMap(), "from-cli-or-process-env")).isEqualTo("from-cli-or-process-env")
        assertThat(resolveGcsBucket(mapOf("GCS_BUCKET" to " "), "from-cli-or-process-env"))
            .isEqualTo("from-cli-or-process-env")
    }

    @Test
    fun `blank everywhere means no bucket`() {
        assertThat(resolveGcsBucket(emptyMap(), "")).isNull()
    }
}
