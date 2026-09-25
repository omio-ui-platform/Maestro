package maestro.cli.util

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Outcome of an upload: the object's URL, or a one-line reason it is not in the bucket. */
sealed interface UploadResult {
    data class Uploaded(val url: String) : UploadResult
    data class Failed(val detail: String) : UploadResult
}

/**
 * Utility class for uploading files to Google Cloud Storage using gcloud CLI.
 *
 * Authentication is handled via gcloud CLI's default credentials:
 * - Application Default Credentials (gcloud auth application-default login)
 * - Service account (gcloud auth activate-service-account)
 * - Workload Identity (on GCP)
 *
 * The bucket name is configured via the GCS_BUCKET environment variable.
 */
object GcsUploader {

    private val logger = LoggerFactory.getLogger(GcsUploader::class.java)

    private val UPLOAD_TIMEOUT = 120.seconds

    /** Longest gcloud output line carried into [UploadResult.Failed] (it ends up on one console line). */
    private const val MAX_DETAIL_LENGTH = 200

    /**
     * Uploads a file to Google Cloud Storage using gcloud CLI.
     *
     * @param file The file to upload
     * @param objectName The name/path of the object in the bucket (e.g., "recordings/flow-name.mp4")
     * @param bucketName The GCS bucket name (defaults to GCS_BUCKET env var)
     * @return [UploadResult.Uploaded] with the object URL, or [UploadResult.Failed] saying why not --
     *   gcloud's exit code and last output line, a timeout, or the launch exception -- so callers can
     *   surface the cause where CI keeps it (maestro.log is not archived).
     */
    fun uploadFile(
        file: File,
        objectName: String,
        bucketName: String? = System.getenv("GCS_BUCKET")
    ): UploadResult {
        if (bucketName.isNullOrBlank()) {
            logger.debug("GCS_BUCKET environment variable not set, skipping upload")
            return UploadResult.Failed("no bucket")
        }

        if (!file.exists()) {
            logger.warn("File does not exist: ${file.absolutePath}")
            return UploadResult.Failed("local file missing")
        }

        val gcsPath = "gs://$bucketName/$objectName"
        val command = listOf("gcloud", "storage", "cp", file.absolutePath, gcsPath)

        logger.info("[GCS-DEBUG] Executing: ${command.joinToString(" ")}")

        return try {
            when (val outcome = runCommand(command, UPLOAD_TIMEOUT)) {
                is CommandOutcome.TimedOut -> {
                    logger.error("[GCS-DEBUG] gcloud command timed out after $UPLOAD_TIMEOUT")
                    logger.error("[GCS-DEBUG] Output: ${outcome.output}")
                    UploadResult.Failed(
                        listOfNotNull("timed out after $UPLOAD_TIMEOUT", lastMeaningfulLine(outcome.output))
                            .joinToString(": ")
                    )
                }
                is CommandOutcome.Exited -> if (outcome.exitCode == 0) {
                    // Use authenticated URL (requires Google login, but works with private buckets)
                    val url = "https://storage.cloud.google.com/$bucketName/$objectName"
                    logger.info("[GCS-DEBUG] Upload completed successfully")
                    logger.info("Uploaded ${file.name} to $url")
                    UploadResult.Uploaded(url)
                } else {
                    logger.error("[GCS-DEBUG] gcloud command failed with exit code ${outcome.exitCode}")
                    logger.error("[GCS-DEBUG] Output: ${outcome.output}")
                    UploadResult.Failed(
                        listOfNotNull("gcloud exit ${outcome.exitCode}", lastMeaningfulLine(outcome.output))
                            .joinToString(": ")
                    )
                }
            }
        } catch (e: Exception) {
            // e.g. IOException "Cannot run program \"gcloud\"" when it is not on PATH.
            logger.error("[GCS-DEBUG] Failed to upload file to GCS: ${e.message}", e)
            UploadResult.Failed(listOfNotNull(e.javaClass.simpleName, e.message?.let(::oneLine)).joinToString(": "))
        }
    }

    internal sealed interface CommandOutcome {
        data class Exited(val exitCode: Int, val output: String) : CommandOutcome
        data class TimedOut(val output: String) : CommandOutcome
    }

    /**
     * Runs [command] and waits at most [timeout] for it. Output (stdout + stderr) is drained on its
     * own thread: reading it to EOF on this thread first -- as this used to -- blocks until the
     * process exits, so the timeout never fired and a stalled gcloud hung the whole shard. Stdin is
     * closed immediately so a credentials prompt fails fast instead of waiting on it.
     */
    internal fun runCommand(command: List<String>, timeout: Duration): CommandOutcome {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()

        val output = StringBuffer()
        val drainer = thread(isDaemon = true, name = "gcs-uploader-output") {
            try {
                process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
            } catch (e: IOException) {
                // Stream closed under us by destroyForcibly() on timeout -- whatever was read is kept.
            }
        }

        val finished = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        // Bounded: a grandchild that inherited the pipe could keep it open after the process died.
        drainer.join(1_000)

        return if (finished) CommandOutcome.Exited(process.exitValue(), output.toString())
        else CommandOutcome.TimedOut(output.toString())
    }

    /** The last non-blank line of [output] -- where gcloud puts its `ERROR: ...` -- capped for one console line. */
    internal fun lastMeaningfulLine(output: String): String? =
        output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.let(::oneLine)

    private fun oneLine(text: String): String =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").take(MAX_DETAIL_LENGTH)

    /**
     * Sanitizes a single GCS path SEGMENT (a folder name or the final filename) down
     * to object-key-safe characters. Does not touch "/" — callers are responsible
     * for splitting a multi-segment value (e.g. a Jenkins `JOB_NAME` like
     * `e2e/android-stable`) into its own segments and sanitizing each one, so a
     * literal "/" already present in a value can become a real path boundary
     * instead of being stripped.
     *
     * `internal` (not `private`) so the naming convention is directly unit-testable
     * without spawning a real `gcloud` process.
     */
    internal fun sanitizeForObjectName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.-]+"), "-").trim('-')

    /**
     * Builds the recording upload's object PATH (folders + filename) — see
     * [uploadRecording]'s own doc for the convention and why `jobName` becomes real
     * path segments. Pure/no I/O, and `internal` for the same testability reason as
     * [sanitizeForObjectName].
     */
    internal fun buildRecordingObjectName(
        flowName: String,
        buildNumber: String,
        attemptNumber: Int,
        jobName: String?,
    ): String {
        // Jenkins JOB_NAME for a job inside a folder is already "/"-delimited (e.g.
        // "e2e/android-stable") — split on it and sanitize each segment individually
        // so "/" survives as a real folder boundary rather than being collapsed by
        // sanitizeForObjectName into a dash.
        val jobPathPrefix = jobName
            ?.split("/")
            ?.map(::sanitizeForObjectName)
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("/", postfix = "/")
            ?: ""
        return "${jobPathPrefix}${buildNumber}/${attemptNumber}-${flowName}.mp4"
    }

    /**
     * Uploads a recording file to GCS, organized by job and build.
     *
     * Object path: [{jobName}/]{buildNumber}/{attemptNumber}-{flowName}.mp4
     * Example: e2e/android-stable/12345/6-login_flow.mp4
     *
     * `jobName` becomes real GCS "folders" (not a flat filename segment) whenever
     * available, specifically to prevent a collision between two DIFFERENT Jenkins
     * jobs (each with their own independent build-number counter, e.g.
     * `e2e/ios-stable` and `e2e/ios-expo-stable`) that happen to share the same
     * `buildNumber` and produce a failed test with the same flow name around the
     * same time — without it, their recordings can collide on the exact same
     * object key (silently overwriting one another) or be indistinguishable to a
     * downstream recovery step that lists recordings scoped only by build number.
     *
     * @param file The recording file to upload
     * @param flowName The name of the flow
     * @param buildNumber CI build number
     * @param attemptNumber The (1-based) retry attempt this recording is from
     * @param jobName CI job identifier (e.g. Jenkins JOB_NAME, "/"-delimited for a
     *   job inside a folder). Optional: when null/blank, the recording is uploaded
     *   directly under `{buildNumber}/` with no job folder — e.g. a local/dev run
     *   with no Jenkins JOB_NAME set.
     * @param bucketName The GCS bucket name
     * @return See [uploadFile].
     */
    fun uploadRecording(
        file: File,
        flowName: String,
        buildNumber: String,
        attemptNumber: Int,
        jobName: String? = System.getenv("JOB_NAME"),
        bucketName: String? = System.getenv("GCS_BUCKET")
    ): UploadResult {
        // DEBUG LOGS: Environment variables for naming
        logger.info("[GCS-DEBUG] uploadRecording called for flowName=$flowName")
        logger.info("[GCS-DEBUG] jobName=$jobName")
        logger.info("[GCS-DEBUG] buildNumber=$buildNumber")
        logger.info("[GCS-DEBUG] attemptNumber=$attemptNumber")
        logger.info("[GCS-DEBUG] GCS_BUCKET (from System.getenv) -> bucketName=$bucketName")

        val objectName = buildRecordingObjectName(flowName, buildNumber, attemptNumber, jobName)

        logger.info("[GCS-DEBUG] Constructed objectName=$objectName")
        logger.info("[GCS-DEBUG] File to upload: ${file.absolutePath}, exists=${file.exists()}, size=${if (file.exists()) file.length() else "N/A"} bytes")

        return uploadFile(file, objectName, bucketName)
    }

    /**
     * Checks if GCS upload is configured (bucket name is set).
     */
    fun isConfigured(bucketName: String? = System.getenv("GCS_BUCKET")): Boolean {
        return !bucketName.isNullOrBlank()
    }
}
