package maestro.cli.runner

/**
 * Why a flow that FAILED on its last attempt ends without a `[RECORDING]` line.
 *
 * Printed to stdout as `[RECORDING-MISSING] <flow> <reason>`, next to where `[RECORDING]` would
 * have been, so the calling pipeline can tell "no video, and why" apart from "the shard died before
 * it got this far" (no line at all). The `[RECORDING-DEBUG]` / `[GCS-DEBUG]` detail only reaches
 * maestro.log, which CI does not keep. Without this, a missing bucket silently cost every iOS
 * failure its video for days (goeuro/app UP-5720).
 *
 * The marker deliberately does not match the `[RECORDING] <flow> <url>` pattern consumers parse.
 */
internal object RecordingMissingReason {

    const val MARKER = "[RECORDING-MISSING]"

    /** `null` when the recording was uploaded, i.e. a `[RECORDING]` line was printed instead. */
    fun of(
        gcsBucket: String?,
        recordingStarted: Boolean,
        recordingHadContent: Boolean,
        uploaded: Boolean,
        processingError: String?,
    ): String? = when {
        uploaded -> null
        // First: the video may well exist locally, but without a bucket it can never be uploaded.
        gcsBucket.isNullOrBlank() -> "no-bucket (GCS_BUCKET is not in Maestro's process env and --gcs-bucket was not passed)"
        !recordingStarted -> "not-recorded (screen recording failed to start)"
        processingError != null -> "error (${oneLine(processingError)})"
        !recordingHadContent -> "empty-recording (no video bytes were written)"
        else -> "upload-failed (gcloud storage cp failed or timed out)"
    }

    fun line(flowName: String, reason: String): String = "$MARKER $flowName $reason"

    private fun oneLine(message: String): String =
        message.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "unknown" }
}
