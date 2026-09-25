package maestro.cli.runner

import maestro.Maestro
import maestro.cli.CliError
import maestro.device.Device
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import maestro.cli.report.SingleScreenFlowAIOutput
import maestro.cli.report.FlowAIOutput
import maestro.cli.report.TestDebugReporter
import maestro.cli.report.TestSuiteReporter
import maestro.cli.util.FileUtils.toCwdRelativeOrAbsoluteString
import maestro.cli.util.PrintUtils
import maestro.cli.view.ErrorViewUtils
import maestro.cli.view.TestSuiteStatusView
import maestro.cli.view.TestSuiteStatusView.TestSuiteViewModel
import maestro.orchestra.Orchestra
import maestro.orchestra.debug.FlowDebugOutput
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.orchestra.yaml.YamlCommandReader
import okio.Sink
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import kotlin.system.measureTimeMillis
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import maestro.cli.util.GcsUploader
import maestro.cli.util.UploadResult
import kotlin.coroutines.cancellation.CancellationException
import maestro.cli.util.ScreenshotUtils
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import okio.buffer
import okio.sink

/**
 * Similar to [TestRunner], but:
 *  * can run many flows at once
 *  * does not support continuous mode
 *
 *  Does not care about sharding. It only has to know the index of the shard it's running it, for logging purposes.
 */
class TestSuiteInteractor(
    private val maestro: Maestro,
    private val device: Device? = null,
    private val reporter: TestSuiteReporter,
    private val shardIndex: Int? = null,
    private val recordingEnabled: Boolean = true,
    /**
     * Record every attempt and keep the recording when the flow produced AI findings, not only when
     * it failed. Off by default, and set by the localization job alone -- see `shouldRecord` and
     * `shouldUpload` below for what it changes and why the default must stay off.
     */
    private val recordOnFindings: Boolean = false,
    private val gcsBucket: String? = null,
    private val attemptNumber: Int = 1,
    private val maxRetries: Int = 1,
    private val buildName: String? = null,
    private val buildNumber: String? = null,
    private val deviceName: String? = null,
    private val jobName: String? = null,
    private val captureSteps: Boolean = false,
    private val captureFullArtifacts: Boolean = false,
    private val testOutputDir: Path? = null,
) {

    private val logger = LoggerFactory.getLogger(TestSuiteInteractor::class.java)
    private val shardPrefix = shardIndex?.let { "[shard ${it + 1}] " }.orEmpty()

    suspend fun runTestSuite(
        executionPlan: WorkspaceExecutionPlanner.ExecutionPlan,
        reportOut: Sink?,
        env: Map<String, String>,
        debugOutputPath: Path,
        deviceId: String? = null,
    ): TestExecutionSummary {
        if (executionPlan.flowsToRun.isEmpty() && executionPlan.sequence.flows.isEmpty()) {
            throw CliError("${shardPrefix}No flows returned from the tag filter used")
        }

        val flowResults = mutableListOf<TestExecutionSummary.FlowResult>()

        val suiteStartTime = System.currentTimeMillis()

        PrintUtils.message("${shardPrefix}Waiting for flows to complete...")

        var passed = true
        val aiOutputs = mutableListOf<FlowAIOutput>()

        // first run sequence of flows if present
        val flowSequence = executionPlan.sequence
        for (flow in flowSequence.flows) {
            val flowFile = flow.toFile()
            val updatedEnv = env
                .withInjectedShellEnvVars()
                .withDefaultEnvVars(flowFile, deviceId, shardIndex)
            val (result, aiOutput) = runFlow(flowFile, updatedEnv, maestro, debugOutputPath)
            flowResults.add(result)
            aiOutputs.add(aiOutput)

            if (result.status == FlowStatus.ERROR) {
                passed = false
                if (executionPlan.sequence.continueOnFailure != true) {
                    PrintUtils.message("${shardPrefix}Flow ${result.name} failed and continueOnFailure is set to false, aborting running sequential Flows")
                    println()
                    break
                }
            }
        }

        // proceed to run all other Flows
        executionPlan.flowsToRun.forEach { flow ->
            val flowFile = flow.toFile()
            val updatedEnv = env
                .withInjectedShellEnvVars()
                .withDefaultEnvVars(flowFile, deviceId, shardIndex)
            val (result, aiOutput) = runFlow(flowFile, updatedEnv, maestro, debugOutputPath)
            aiOutputs.add(aiOutput)

            if (result.status == FlowStatus.ERROR) {
                passed = false
            }
            flowResults.add(result)
        }


        // Wall-clock elapsed rather than the sum of flow durations, so that the suite's reported
        // duration and its startTime describe the same window in the JUnit report.
        val suiteDuration = (System.currentTimeMillis() - suiteStartTime).milliseconds

        TestSuiteStatusView.showSuiteResult(
            TestSuiteViewModel(
                status = if (passed) FlowStatus.SUCCESS else FlowStatus.ERROR,
                duration = suiteDuration,
                shardIndex = shardIndex,
                flows = flowResults
                    .map {
                        TestSuiteViewModel.FlowResult(
                            name = it.name,
                            status = it.status,
                            duration = it.duration,
                        )
                    },
            ),
            uploadUrl = ""
        )

        val summary = TestExecutionSummary(
            passed = passed,
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = passed,
                    flows = flowResults,
                    duration = suiteDuration,
                    startTime = suiteStartTime,
                    deviceName = device?.description,
                )
            ),
            passedCount = flowResults.count { it.status == FlowStatus.SUCCESS },
            totalTests = flowResults.size
        )

        if (reportOut != null) {
            reporter.report(
                summary,
                reportOut,
            )
        }

        // TODO(bartekpacia): Should it also be saving to debugOutputPath?
        TestDebugReporter.saveSuggestions(aiOutputs, debugOutputPath)

        return summary
    }

    private suspend fun runFlow(
        flowFile: File,
        env: Map<String, String>,
        maestro: Maestro,
        debugOutputPath: Path,
    ): Pair<TestExecutionSummary.FlowResult, FlowAIOutput> {
        // TODO(bartekpacia): merge TestExecutionSummary with AI suggestions
        //  (i.e. consider them also part of the test output)
        //  See #1973

        var flowStatus: FlowStatus
        var errorMessage: String? = null

        val aiOutput = FlowAIOutput(
            flowName = flowFile.nameWithoutExtension,
            flowFile = flowFile,
        )
        val commands = YamlCommandReader
            .readCommands(flowFile.toPath())
            .withEnv(env)

        val maestroConfig = YamlCommandReader.getConfig(commands)
        val flowName: String = maestroConfig?.name ?: flowFile.nameWithoutExtension

        logger.info("$shardPrefix Running flow $flowName")
        PrintUtils.message("${shardPrefix}Running: $flowName")

        // Record on the last attempt only, because a flow that is about to be retried does not need
        // a video -- the retry's own recording supersedes it. Requires BUILD_NAME, BUILD_NUMBER and
        // DEVICE_NAME, which name the object; no defaults.
        //
        // `recordOnFindings` lifts the last-attempt condition, and it has to: a flow that PASSES is
        // never retried, so it is only ever on its last attempt by accident of the counter. Without
        // this, a passing flow with findings could never be recorded at all and relaxing the upload
        // condition alone would achieve nothing.
        //
        // The cost is that every attempt records, and attempts that turn out not to need the file
        // discard it below. That is unavoidable: whether a flow finds anything is not knowable until
        // it has run, and recording cannot start retroactively.
        val isLastAttempt = attemptNumber >= maxRetries
        val hasRequiredEnvVars = buildName != null && buildNumber != null && deviceName != null
        val shouldRecord = recordingEnabled && (isLastAttempt || recordOnFindings) && hasRequiredEnvVars

        // DEBUG LOGS: Recording decision
        logger.info("${shardPrefix}[RECORDING-DEBUG] Flow: $flowName")
        logger.info("${shardPrefix}[RECORDING-DEBUG] recordingEnabled=$recordingEnabled, attemptNumber=$attemptNumber, maxRetries=$maxRetries")
        logger.info("${shardPrefix}[RECORDING-DEBUG] isLastAttempt=$isLastAttempt (attemptNumber >= maxRetries = $attemptNumber >= $maxRetries)")
        logger.info("${shardPrefix}[RECORDING-DEBUG] hasRequiredEnvVars=$hasRequiredEnvVars (buildName=$buildName, buildNumber=$buildNumber, deviceName=$deviceName)")
        logger.info("${shardPrefix}[RECORDING-DEBUG] recordOnFindings=$recordOnFindings")
        logger.info("${shardPrefix}[RECORDING-DEBUG] shouldRecord=$shouldRecord (recordingEnabled && (isLastAttempt || recordOnFindings) && hasRequiredEnvVars)")
        logger.info("${shardPrefix}[RECORDING-DEBUG] gcsBucket=${gcsBucket ?: "NOT SET"}")
        logger.info("${shardPrefix}[RECORDING-DEBUG] testOutputDir=$testOutputDir")

        if (!isLastAttempt && recordingEnabled) {
            logger.info("${shardPrefix}Skipping recording for flow $flowName (attempt $attemptNumber of $maxRetries)")
        }

        // Use testOutputDir if provided, otherwise use a temp directory for recordings
        // This allows GCS upload to work even without --output flag
        val recordingDir = if (shouldRecord) {
            val dir = testOutputDir?.resolve("recordings")
                ?: File(System.getProperty("java.io.tmpdir"), "maestro-recordings").toPath()
            dir.toFile().mkdirs()
            logger.info("${shardPrefix}[RECORDING-DEBUG] Using recording directory: $dir")
            dir
        } else null

        val recordingFile = if (shouldRecord && recordingDir != null) {
            recordingDir.resolve("${flowFile.nameWithoutExtension}.mp4").toFile()
        } else null
        val recordingSink = recordingFile?.sink()?.buffer()
        val screenRecording = if (shouldRecord && recordingSink != null) {
            try {
                logger.info("${shardPrefix}Starting screen recording for flow $flowName (last attempt)")
                maestro.startScreenRecording(recordingSink)
            } catch (e: Exception) {
                logger.warn("${shardPrefix}Failed to start screen recording: ${e.message}")
                // Nothing will ever stop this recording, so release its sink and empty file here --
                // the cleanup below only runs for a recording that started.
                runCatching { recordingSink.close() }
                runCatching { recordingFile?.delete() }
                null
            }
        } else null


        // Per-flow folder ArtifactsGenerator writes the bundle into (see BundleLayout).
        val flowDir = TestDebugReporter.createFlowDir(debugOutputPath, flowName, shardIndex)

        var debugOutput = FlowDebugOutput()
        val flowStartTime = System.currentTimeMillis()
        val flowTimeMillis = measureTimeMillis {
            try {
                val orchestra = Orchestra(
                    maestro = maestro,
                    artifactsDir = flowDir,
                    screenshotsDir = testOutputDir?.resolve("screenshots"),
                    captureFullArtifacts = captureFullArtifacts,
                    listeners = listOf(CliConsoleListener(shardPrefix)),
                    onCommandFailed = { _, _, _ -> Orchestra.ErrorResolution.FAIL },
                    onCommandGeneratedOutput = { command, defects, screenshot ->
                        logger.info("${shardPrefix}${command.description()} generated output")
                        val screenshotPath = ScreenshotUtils.writeAIscreenshot(screenshot)
                        aiOutput.screenOutputs.add(
                            SingleScreenFlowAIOutput(
                                screen = command.description(),
                                screenshotPath = screenshotPath,
                                defects = defects,
                            )
                        )
                    },
                )

                val result = orchestra.runFlow(commands)
                flowStatus = if (result.success) FlowStatus.SUCCESS else FlowStatus.ERROR
                debugOutput = result.debugOutput
            } catch (e: Exception) {
                logger.error("${shardPrefix}Failed to complete flow", e)
                flowStatus = FlowStatus.ERROR
                errorMessage = ErrorViewUtils.exceptionToMessage(e)
            }
        }

        // WHICH RECORDINGS ARE WORTH KEEPING:
        //
        //   completed, no findings              discard  -- nothing to look at
        //   completed, findings                 UPLOAD   -- it will not run again, so this
        //                                                   is the only chance to keep it
        //   not completed, not last attempt     discard  -- the retry's video supersedes it
        //   not completed, last attempt         UPLOAD   -- the video IS the diagnostic
        //
        // `hasFindings` is derived, not configured: `aiOutput` is filled by
        // `onCommandGeneratedOutput` while the flow runs, just above, so by here it already
        // knows what the run found.
        //
        // Gated on `recordOnFindings` so this is the localization job's behaviour alone.
        // Without the gate, any flow that passes while holding AI defects would start
        // uploading -- `assertNoDefectsWithAI` writes into this same `aiOutput`, and other
        // suites use it.
        //
        // `hasFindings` only keeps a PASSING flow's video. A failed attempt with findings
        // still waits for its last attempt like any failure: the retry records its own video.
        // (Build 90 shipped `hasFindings || (ERROR && isLastAttempt)`, which also uploaded
        // every failed non-final attempt that had findings -- not what the table above says.)
        //
        // Decided here, outside the try below, so a failure while stopping the recording still
        // knows the video was wanted and reports why it is missing.
        val hasFindings = recordOnFindings && aiOutput.screenOutputs.any { it.defects.isNotEmpty() }
        val videoWanted = shouldRecord && (
            (flowStatus == FlowStatus.SUCCESS && hasFindings) ||
                (flowStatus == FlowStatus.ERROR && isLastAttempt)
            )

        // Stop screen recording and handle upload/cleanup
        var recordingUploaded = false
        var recordingHadContent = false
        var uploadFailure: String? = null
        var recordingError: String? = null
        if (screenRecording != null) {
            try {
                logger.info("${shardPrefix}Stopping screen recording for flow $flowName")
                screenRecording.close()
                recordingSink?.close()
                recordingHadContent = (recordingFile?.length() ?: 0L) > 0L

                // An empty file is not uploaded: it would be linked as a normal "Rec" that does not
                // play. It is reported as `empty-recording` instead.
                val shouldUpload = videoWanted && gcsBucket != null && recordingFile != null && recordingHadContent

                // DEBUG LOGS: Upload decision
                logger.info("${shardPrefix}[RECORDING-DEBUG] Post-execution state:")
                logger.info("${shardPrefix}[RECORDING-DEBUG] flowStatus=$flowStatus")
                logger.info("${shardPrefix}[RECORDING-DEBUG] recordingFile=${recordingFile?.absolutePath ?: "NULL"}")
                logger.info("${shardPrefix}[RECORDING-DEBUG] recordingFile.exists=${recordingFile?.exists()}")
                logger.info("${shardPrefix}[RECORDING-DEBUG] gcsBucket=${gcsBucket ?: "NOT SET"}")
                logger.info("${shardPrefix}[RECORDING-DEBUG] hasFindings=$hasFindings, isLastAttempt=$isLastAttempt, recordingHadContent=$recordingHadContent")
                logger.info("${shardPrefix}[RECORDING-DEBUG] shouldUpload=$shouldUpload (videoWanted=$videoWanted && gcsBucket!=null && recordingFile!=null && recordingHadContent)")

                if (shouldUpload && recordingFile != null) {
                    when (val upload = GcsUploader.uploadRecording(
                        file = recordingFile,
                        flowName = flowFile.nameWithoutExtension,
                        buildNumber = buildNumber!!,
                        attemptNumber = attemptNumber,
                        jobName = jobName,
                        bucketName = gcsBucket
                    )) {
                        is UploadResult.Uploaded -> {
                            recordingUploaded = true
                            logger.info("${shardPrefix}Recording uploaded to GCS: ${upload.url}")
                            // Output in parseable format for external pipelines
                            PrintUtils.message("[RECORDING] ${flowFile.nameWithoutExtension} ${upload.url}")
                        }
                        is UploadResult.Failed -> uploadFailure = upload.detail
                    }
                } else if (flowStatus == FlowStatus.SUCCESS) {
                    logger.info("${shardPrefix}Test passed, skipping recording upload for flow $flowName")
                }

                // Always delete local recording file after processing
                // (we either uploaded it to GCS or don't need it)
                if (recordingFile?.exists() == true) {
                    val deleted = recordingFile.delete()
                    if (deleted) {
                        logger.info("${shardPrefix}Deleted local recording file: ${recordingFile.absolutePath}")
                    } else {
                        logger.warn("${shardPrefix}Failed to delete local recording file: ${recordingFile.absolutePath}")
                    }
                }
            } catch (e: Throwable) {
                // Throwable, not Exception: a recorder's close() can rethrow a LinkageError (e.g.
                // NoClassDefFoundError from a dependency skew), which would otherwise escape runFlow
                // and abort every remaining flow in the shard. Cancellation and VM errors still propagate.
                if (e is CancellationException || e is VirtualMachineError) throw e
                recordingError = RecordingMissingReason.describe(e)
                logger.warn("${shardPrefix}Failed to process screen recording: ${e.message}")
                // Attempt cleanup on error too
                try {
                    recordingFile?.delete()
                } catch (cleanupError: Exception) {
                    logger.warn("${shardPrefix}Failed to cleanup recording file: ${cleanupError.message}")
                }
            }
        }

        // A flow whose video was wanted always ends in exactly one stdout line: `[RECORDING] <flow>
        // <url>` above, or this one saying why there is no video.
        if (videoWanted) {
            RecordingMissingReason.of(
                gcsBucket = gcsBucket,
                recordingStarted = screenRecording != null,
                recordingHadContent = recordingHadContent,
                uploaded = recordingUploaded,
                uploadFailure = uploadFailure,
                processingError = recordingError,
            )?.let { reason ->
                logger.warn("${shardPrefix}No recording uploaded for flow $flowName: $reason")
                PrintUtils.message(RecordingMissingReason.line(flowFile.nameWithoutExtension, reason))
            }
        }

        val flowDuration = flowTimeMillis.milliseconds
        PrintUtils.message("${shardPrefix}Flow '$flowName' execution ended in ${(flowTimeMillis / 1000f).roundToLong().seconds} seconds")
        // FIXME(bartekpacia): Save AI output as well

        TestSuiteStatusView.showFlowCompletion(
            TestSuiteViewModel.FlowResult(
                name = flowName,
                status = flowStatus,
                duration = flowDuration,
                shardIndex = shardIndex,
                error = debugOutput.exception?.message,
            )
        )

        // Extract step information if captureSteps is enabled
        val steps = if (captureSteps) {
            debugOutput.commands.entries
                .sortedBy { it.value.sequenceNumber }
                .mapIndexed { index, (command, metadata) ->
                    val durationStr = when (val duration = metadata.duration) {
                        null -> "<1ms"
                        else -> if (duration >= 1000) {
                            "%.1fs".format(duration / 1000.0)
                        } else {
                            "${duration}ms"
                        }
                    }
                    val status = metadata.status?.toString() ?: "UNKNOWN"
                    // Use evaluated command for interpolated labels, fallback to original
                    val displayCommand = metadata.evaluatedCommand ?: command
                    TestExecutionSummary.StepResult(
                        description = "${index + 1}. ${displayCommand.description()}",
                        status = status,
                        duration = durationStr,
                    )
                }
        } else {
            emptyList()
        }

        return Pair(
            first = TestExecutionSummary.FlowResult(
                name = flowName,
                fileName = flowFile.nameWithoutExtension,
                filePath = flowFile.toPath().toCwdRelativeOrAbsoluteString(),
                status = flowStatus,
                failure = if (flowStatus == FlowStatus.ERROR) {
                    TestExecutionSummary.Failure(
                        message = shardPrefix + (errorMessage ?: debugOutput.exception?.message ?: "Unknown error"),
                    )
                } else null,
                duration = flowDuration,
                startTime = flowStartTime,
                properties = maestroConfig?.properties,
                tags = maestroConfig?.tags,
                steps = steps,
            ),
            second = aiOutput,
        )
    }

}
