package maestro.drivers

import com.google.common.truth.Truth.assertThat
import dadb.AdbShellResponse
import dadb.AdbStream
import dadb.Dadb
import dadb.InstallResult
import dadb.SyncResult
import dadb.UninstallResult
import io.mockk.every
import io.mockk.mockk
import maestro.android.AndroidDeviceConnection
import maestro.android.AndroidOperationFailedException
import okio.Sink
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class AndroidDriverAddMediaTest {
    private class Device : Dadb {
        val copies = mutableListOf<Pair<File, String>>()
        val commands = mutableListOf<String>()
        var result: SyncResult = SyncResult.Success
        override fun push(src: File, remotePath: String, mode: Int, lastModifiedMs: Long): SyncResult {
            copies.add(src to remotePath)
            return result
        }
        override fun shell(command: String): AdbShellResponse {
            commands.add(command)
            return mockk { every { exitCode } returns 0; every { output } returns "" }
        }
        override fun open(destination: String): AdbStream = error("unexpected open")
        override fun supportsFeature(feature: String) = true
        override fun install(file: File, vararg options: String): InstallResult = error("unexpected install")
        override fun uninstall(packageName: String): UninstallResult = error("unexpected uninstall")
        override fun pull(dst: File, remotePath: String): SyncResult = error("unexpected pull")
        override fun pull(sink: Sink, remotePath: String): SyncResult = error("unexpected pull")
        override fun close() {}
    }

    private val files = TempFileHandler()
    private val fixtures = files.createTempDirectory("pdf-import-")
    private fun fixture(name: String) = File(fixtures, name).apply { writeText("%PDF-1.4") }
    @AfterEach
    fun cleanup() = files.close()

    private val device = Device()
    private val driver = AndroidDriver(AndroidDeviceConnection.forTest(dadb = device))

    @Test
    fun `PDF is copied to Downloads using its original filename`() {
        val pdf = fixture("boarding pass.PDF")
        driver.addMedia(listOf(pdf))
        assertThat(device.copies).containsExactly(pdf to "/sdcard/Download/boarding pass.PDF")
        assertThat(device.commands.last()).contains("boarding%20pass.PDF")
    }

    @Test
    fun `PDF copy failure is surfaced instead of reporting success`() {
        device.result = SyncResult.Failure("disk full")
        val error = assertThrows<AndroidOperationFailedException> { driver.addMedia(listOf(fixture("ticket.pdf"))) }
        assertThat(error).hasMessageThat().contains("disk full")
        assertThat(device.commands.none { it.contains("MEDIA_SCANNER_SCAN_FILE") }).isTrue()
    }

    @Test
    fun `unsupported extensions fail before opening or copying a file`() {
        assertThrows<IllegalArgumentException> { driver.addMedia(listOf(File("ticket.txt"))) }
        assertThat(device.copies).isEmpty()
    }

    @Test
    fun `filenames containing quotes cannot become shell commands`() {
        driver.addMedia(listOf(fixture("pass' name.pdf")))
        assertThat(device.commands.last()).contains("pass'\\''%20name.pdf")
    }
}
