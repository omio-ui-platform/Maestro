package maestro.cli.runner

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RecordingMissingReasonTest {

    private fun reason(
        gcsBucket: String? = "maestro-recordings-dev",
        recordingStarted: Boolean = true,
        recordingHadContent: Boolean = true,
        uploaded: Boolean = false,
        uploadFailure: String? = null,
        processingError: String? = null,
    ) = RecordingMissingReason.of(gcsBucket, recordingStarted, recordingHadContent, uploaded, uploadFailure, processingError)

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
    fun `an upload failure carries the uploader's own detail`() {
        assertThat(reason(uploadFailure = "gcloud exit 1: ERROR: (gcloud.storage.cp) 403 does not have storage.objects.create access"))
            .isEqualTo("upload-failed (gcloud exit 1: ERROR: (gcloud.storage.cp) 403 does not have storage.objects.create access)")
        assertThat(reason()).isEqualTo("upload-failed (unknown)")
    }

    @Test
    fun `describe keeps the exception type, with or without a message`() {
        assertThat(RecordingMissingReason.describe(java.io.IOException(""))).isEqualTo("IOException")
        assertThat(RecordingMissingReason.describe(java.net.SocketTimeoutException("timeout")))
            .isEqualTo("SocketTimeoutException: timeout")
    }

    @Test
    fun `free text is one capped line with Jansi markup delimiters neutralised`() {
        val result = reason(processingError = "boom @|red oops|@ " + "x".repeat(500))!!

        assertThat(result).doesNotContain("@|")
        assertThat(result).doesNotContain("|@")
        assertThat(result.length).isLessThan(230)
    }

    @Test
    fun `the printed line does not match the RECORDING url pattern consumers parse`() {
        val line = RecordingMissingReason.line("login", reason()!!)

        assertThat(line).startsWith("[RECORDING-MISSING] login upload-failed")
        assertThat(Regex("""\[RECORDING]\s+(\S+)\s+(https://\S+)""").containsMatchIn(line)).isFalse()
    }
}
