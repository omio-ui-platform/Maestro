package util

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class LocalSimulatorAddMediaTest {
    private val files = TempFileHandler()
    private val simulator = LocalSimulatorUtils(files)
    private val root = files.createTempDirectory("pdf-import-")
    private val capturedFiles = mutableListOf<File>()
    private val group = File(root, "Files container").apply { mkdirs() }

    init {
        mockkObject(CommandLineUtils)
        every { CommandLineUtils.runCommand(match { it.firstOrNull() == "plutil" }, outputFile = any()) } returns mockk()
    }

    @AfterEach
    fun cleanup() {
        unmockkObject(CommandLineUtils)
        files.close()
    }

    private fun containerResult(value: String) {
        every {
            CommandLineUtils.runCommand(
                listOf("xcrun", "simctl", "listapps", "device-123"),
                outputFile = any(),
                errorFile = any(),
            )
        } answers {
            val metadata = thirdArg<File>()
            val diagnostics = arg<File>(4)
            capturedFiles.addAll(listOf(metadata, diagnostics))
            metadata.writeText(value)
            diagnostics.writeText("simctl warning")
            mockk<Process>()
        }
    }

    @Test
    fun `PDF is copied into the assigned simulator Files provider and replaced on retry`() {
        containerResult("""{"com.apple.DocumentsApp":{"GroupContainers":{"group.com.apple.FileProvider.LocalStorage":"${group.toURI()}"}}}""")
        val pdf = File(root, "boarding pass.PDF").apply { writeText("first PDF") }

        simulator.addMedia("device-123", pdf.path)
        pdf.writeText("updated PDF")
        simulator.addMedia("device-123", pdf.path)

        assertThat(File(group, "File Provider Storage/boarding pass.PDF").readText()).isEqualTo("updated PDF")
        assertThat(capturedFiles).hasSize(4)
        assertThat(capturedFiles.none { it.exists() }).isTrue()
    }

    @Test
    fun `missing Files provider cannot fall back to a relative destination`() {
        containerResult("{}")
        val pdf = File(root, "ticket.pdf").apply { writeText("PDF") }

        assertThrows<IllegalStateException> { simulator.addMedia("device-123", pdf.path) }
        assertThat(capturedFiles.none { it.exists() }).isTrue()
    }

    @Test
    fun `images still use simctl addmedia`() {
        every { CommandLineUtils.runCommand(listOf("xcrun", "simctl", "addmedia", "device-123", "image.png")) } returns mockk()

        simulator.addMedia("device-123", "image.png")

        verify { CommandLineUtils.runCommand(listOf("xcrun", "simctl", "addmedia", "device-123", "image.png")) }
    }
}
