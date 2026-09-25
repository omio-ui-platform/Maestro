package maestro.cli.runner

/**
 * Why a flow whose video was WANTED -- a failed last attempt, or (with `recordOnFindings`) a passing
 * flow with findings -- ends without a `[RECORDING]` line.
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

    /** Longest free-text detail carried into a reason (it is one console line). */
    private const val MAX_DETAIL_LENGTH = 200

    /**
     * `null` when the recording was uploaded, i.e. a `[RECORDING]` line was printed instead.
     *
     * @param uploadFailure the uploader's own reason when an upload was attempted and failed.
     */
    fun of(
        gcsBucket: String?,
        recordingStarted: Boolean,
        recordingHadContent: Boolean,
        uploaded: Boolean,
        uploadFailure: String?,
        processingError: String?,
    ): String? = when {
        uploaded -> null
        // First: the video may well exist locally, but without a bucket it can never be uploaded.
        gcsBucket.isNullOrBlank() -> "no-bucket (GCS_BUCKET is not set via -e, the process env or --gcs-bucket)"
        !recordingStarted -> "not-recorded (screen recording failed to start)"
        processingError != null -> "error (${oneLine(processingError)})"
        // Checked before the upload, which skips an empty file rather than linking an unplayable video.
        !recordingHadContent -> "empty-recording (no video bytes were written)"
        else -> "upload-failed (${uploadFailure?.let(::oneLine) ?: "unknown"})"
    }

    /** `IOException: Broken pipe` -- keeps the type, which a bare message (or `unknown`) loses. */
    fun describe(error: Throwable): String =
        listOfNotNull(error.javaClass.simpleName, error.message?.takeIf { it.isNotBlank() }).joinToString(": ")

    fun line(flowName: String, reason: String): String = "$MARKER $flowName $reason"

    /**
     * One trimmed, capped line. `@|` / `|@` are Jansi markup delimiters -- `PrintUtils.message` wraps
     * its text in `@|cyan ...|@`, so raw gcloud/exception text containing them would end the span
     * early or, as `@|word`, make the renderer throw.
     */
    private fun oneLine(message: String): String =
        message.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            .replace("@|", "@ |").replace("|@", "| @")
            .take(MAX_DETAIL_LENGTH)
            .ifEmpty { "unknown" }
}
