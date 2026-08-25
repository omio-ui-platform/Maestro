package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GcsUploaderTest {

    @Test
    fun `object path is jobName folders, then buildNumber, then attemptNumber-flowName`() {
        val objectName = GcsUploader.buildRecordingObjectName(
            flowName = "login_flow",
            buildNumber = "12345",
            attemptNumber = 6,
            jobName = "e2e/android-stable",
        )

        assertThat(objectName).isEqualTo("e2e/android-stable/12345/6-login_flow.mp4")
    }

    @Test
    fun `a job nested more than one folder deep produces the matching number of path segments`() {
        val objectName = GcsUploader.buildRecordingObjectName(
            flowName = "login_flow",
            buildNumber = "1",
            attemptNumber = 1,
            jobName = "e2e/ios/expo-stable",
        )

        assertThat(objectName).isEqualTo("e2e/ios/expo-stable/1/1-login_flow.mp4")
    }

    @Test
    fun `nesting depth is unbounded - e2e slash android slash android-stable produces three real folders`() {
        val objectName = GcsUploader.buildRecordingObjectName(
            flowName = "login_flow",
            buildNumber = "12345",
            attemptNumber = 6,
            jobName = "e2e/android/android-stable",
        )

        assertThat(objectName).isEqualTo("e2e/android/android-stable/12345/6-login_flow.mp4")
    }

    @Test
    fun `null jobName omits the job folder entirely rather than leaving an empty segment`() {
        val objectName = GcsUploader.buildRecordingObjectName(
            flowName = "login_flow",
            buildNumber = "12345",
            attemptNumber = 1,
            jobName = null,
        )

        assertThat(objectName).isEqualTo("12345/1-login_flow.mp4")
    }

    @Test
    fun `blank jobName is treated the same as null`() {
        val objectName = GcsUploader.buildRecordingObjectName(
            flowName = "login_flow",
            buildNumber = "12345",
            attemptNumber = 1,
            jobName = "   ",
        )

        assertThat(objectName).isEqualTo("12345/1-login_flow.mp4")
    }

    @Test
    fun `unsafe characters within a job name segment are sanitized without disturbing the folder boundary`() {
        val objectName = GcsUploader.buildRecordingObjectName(
            flowName = "login_flow",
            buildNumber = "12345",
            attemptNumber = 1,
            jobName = "e2e/android stable!!",
        )

        assertThat(objectName).isEqualTo("e2e/android-stable/12345/1-login_flow.mp4")
    }

    @Test
    fun `sanitizeForObjectName strips unsafe characters and trims leading-trailing dashes`() {
        assertThat(GcsUploader.sanitizeForObjectName("android stable!!")).isEqualTo("android-stable")
        assertThat(GcsUploader.sanitizeForObjectName("  leading and trailing  ")).isEqualTo("leading-and-trailing")
        assertThat(GcsUploader.sanitizeForObjectName("already-safe_name.1")).isEqualTo("already-safe_name.1")
    }

    @Test
    fun `two different jobs sharing the same build number no longer collide on the same object path`() {
        val iosStable = GcsUploader.buildRecordingObjectName(
            flowName = "login_flow",
            buildNumber = "10",
            attemptNumber = 6,
            jobName = "e2e/ios-stable",
        )
        val iosExpoStable = GcsUploader.buildRecordingObjectName(
            flowName = "login_flow",
            buildNumber = "10",
            attemptNumber = 6,
            jobName = "e2e/ios-expo-stable",
        )

        assertThat(iosStable).isNotEqualTo(iosExpoStable)
    }
}
