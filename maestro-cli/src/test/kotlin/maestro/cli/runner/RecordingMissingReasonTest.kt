package maestro.cli.runner

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RecordingMissingReasonTest {

    private fun reason(
        gcsBucket: String? = "maestro-recordings-dev",
        recordingStarted: Boolean = true,
        recordingHadContent: Boolean = true,
        uploaded: Boolean = false,
        processingError: String? = null,
    ) = RecordingMissingReason.of(gcsBucket, recordingStarted, recordingHadContent, uploaded, processingError)

    @Test
    fun `an uploaded recording has no missing reason`() {
        assertThat(reason(uploaded = true)).isNull()
    }

    @Test
    fun `a missing bucket is reported first, even though a video was recorded`() {
        assertThat(reason(gcsBucket = null)).startsWith("no-bucket")
        assertThat(reason(gcsBucket = "")).startsWith("no-bucket")
        assertThat(reason(gcsBucket = null, recordingStarted = false)).startsWith("no-bucket")
    }

    @Test
    fun `a recording that never started is not-recorded`() {
        assertThat(reason(recordingStarted = false, recordingHadContent = false)).startsWith("not-recorded")
    }

    @Test
    fun `an exception while stopping or uploading is reported on one line`() {
        assertThat(reason(processingError = "Broken pipe\n  at okio.Sink\n"))
            .isEqualTo("error (Broken pipe at okio.Sink)")
    }

    @Test
    fun `an empty video file is empty-recording`() {
        assertThat(reason(recordingHadContent = false)).startsWith("empty-recording")
    }

    @Test
    fun `everything in place but no url means the upload failed`() {
        assertThat(reason()).startsWith("upload-failed")
    }

    @Test
    fun `the printed line does not match the RECORDING url pattern consumers parse`() {
        val line = RecordingMissingReason.line("login", reason()!!)

        assertThat(line).startsWith("[RECORDING-MISSING] login upload-failed")
        assertThat(Regex("""\[RECORDING]\s+(\S+)\s+(https://\S+)""").containsMatchIn(line)).isFalse()
    }
}
