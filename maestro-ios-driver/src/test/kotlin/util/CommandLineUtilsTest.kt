package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CommandLineUtilsTest {
    @Test
    fun `failed redirected commands retain their diagnostic output`() {
        TempFileHandler().use { files ->
            val output = files.createTempFile("command-error-", ".log")
            val error = assertThrows<IllegalStateException> {
                CommandLineUtils.runCommand(
                    listOf("/bin/sh", "-c", "printf 'simctl cannot resolve device' >&2; exit 7"),
                    outputFile = output,
                )
            }
            assertThat(error).hasMessageThat().contains("simctl cannot resolve device")
        }
    }

    @Test
    fun `warnings cannot contaminate structured command output`() {
        TempFileHandler().use { files ->
            val metadata = files.createTempFile("metadata-", ".json")
            val diagnostics = files.createTempFile("diagnostics-", ".log")
            CommandLineUtils.runCommand(
                listOf("/bin/sh", "-c", "printf '{\"device\":\"ready\"}'; printf 'simctl warning' >&2"),
                outputFile = metadata,
                errorFile = diagnostics,
            )
            assertThat(jacksonObjectMapper().readTree(metadata).path("device").asText()).isEqualTo("ready")
            assertThat(diagnostics.readText()).contains("simctl warning")
        }
    }

    @Test
    fun `failed structured commands report stderr instead of metadata`() {
        TempFileHandler().use { files ->
            val metadata = files.createTempFile("metadata-", ".json")
            val diagnostics = files.createTempFile("diagnostics-", ".log")
            val error = assertThrows<IllegalStateException> {
                CommandLineUtils.runCommand(
                    listOf("/bin/sh", "-c", "printf '{}'; printf 'device is unavailable' >&2; exit 3"),
                    outputFile = metadata,
                    errorFile = diagnostics,
                )
            }
            assertThat(error).hasMessageThat().contains("device is unavailable")
            assertThat(error).hasMessageThat().contains("exit 3")
        }
    }

    @Test
    fun `existing log callers retain stdout and stderr together`() {
        TempFileHandler().use { files ->
            val log = files.createTempFile("combined-", ".log")
            CommandLineUtils.runCommand(
                listOf("/bin/sh", "-c", "printf 'standard output'; printf 'diagnostic output' >&2"),
                outputFile = log,
            )
            assertThat(log.readText()).contains("standard output")
            assertThat(log.readText()).contains("diagnostic output")
        }
    }
}
