# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Maestro is a UI testing framework for Mobile (Android/iOS) and Web applications. Tests are written in YAML files and executed against real or emulated devices. The framework is built in Kotlin/Java using Gradle.

## Build and Development Commands

### Building the Project

```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew :maestro-cli:build

# Install CLI locally (generates maestro-cli/build/install/maestro/bin/maestro)
./gradlew :maestro-cli:installDist

# Use the locally built CLI instead of globally installed maestro
./maestro-cli/build/install/maestro/bin/maestro <command>

# Or use the convenience script
./maestro <command>
```

### Android Artifacts

Android requires two APKs to run tests:
- `maestro-app.apk` - host app (does nothing)
- `maestro-server.apk` - test runner app (runs HTTP server in UIAutomator test)

```bash
# Build Android artifacts
./gradlew :maestro-android:assemble
./gradlew :maestro-android:assembleAndroidTest

# Artifacts are placed in maestro-android/build/outputs/apk
# and copied to maestro-client/src/main/resources
```

### iOS Artifacts

iOS requires 3 artifacts:
- `maestro-driver-ios` - host app (not installed)
- `maestro-driver-iosUITests-Runner.app` - test runner (runs HTTP server in XCTest)
- `maestro-driver-ios-config.xctestrun` - configuration file

```bash
# Build iOS artifacts
./maestro-ios-xctest-runner/build-maestro-ios-runner.sh

# Artifacts are placed in maestro-ios-driver/src/main/resources
```

#### Running Standalone iOS XCTest Runner

```bash
./maestro-ios-xctest-runner/run-maestro-ios-runner.sh

# Test the runner directly
curl -fsSL -X GET localhost:22087/deviceInfo | jq
curl -fsSL -X POST localhost:22087/touch -d '{"x": 150, "y": 150, "duration": 0.2}'
```

### Testing

```bash
# Run all tests
./gradlew test

# Run integration tests (use FakeDriver, not real devices)
./gradlew :maestro-test:test

# Run tests from IDE
# Open test file and run directly

# Manual testing with local build
./maestro test path/to/test.yaml

# Test YAML files are in maestro-test/src/test/resources/
```

### Device Selection and Persistent Emulator Identifiers

Maestro supports selecting specific devices for test execution using the `--device` flag. You can specify devices by either:
- **Serial number** (e.g., `emulator-5554`) - Changes across Android emulator restarts
- **AVD name** (e.g., `Pixel_6_API_30`) - Persistent across restarts (Android only)
- **UDID** or **Simulator name** (iOS)

```bash
# Run tests on a specific Android emulator by AVD name (persistent)
./maestro test --device "Pixel_6_API_30" flow.yaml

# Run tests on multiple emulators using persistent AVD names
./maestro test --device "Pixel_6_API_30,Pixel_6_API_33" flows/

# Traditional approach using serial numbers (may change across restarts)
./maestro test --device "emulator-5554,emulator-5556" flows/

# Run tests with sharding across specific emulators by AVD name
./maestro test --shard-split 2 --device "Pixel_6_API_30,Pixel_6_API_33" flows/
```

**Why use AVD names?**

Android emulator serial numbers (like `emulator-5554`) change across restarts, making it impossible to consistently assign specific tests to specific emulators. AVD names are persistent and don't change, allowing you to:
- Reliably assign specific test suites to specific emulator configurations
- Create reproducible CI/CD pipelines with stable device assignments
- Ensure the same tests always run on the same emulator setup across restarts

**Implementation details:**

The device resolution logic is in:
- `maestro-client/src/main/java/maestro/device/DeviceService.kt:316-343` - `resolveDeviceIdentifier()` function
- `maestro-cli/src/main/java/maestro/cli/command/TestCommand.kt:298-339` - `resolveDeviceIdentifiers()` function

When you specify a device identifier, Maestro will:
1. First try exact match by serial number/UDID
2. Then try exact match by AVD name/simulator name (retrieved via `adb emu avd name`)
3. Finally, fall back to case-insensitive partial match on description

### Other Commands

```bash
# Lint with Detekt
./gradlew detekt

# Generate project dependency graph
./gradlew :generateDependencyGraph

# Clean build artifacts
./gradlew clean
```

### Maestro Studio (Web UI)

```bash
# Frontend is in maestro-studio/web (React app)
cd maestro-studio/web

# Install dependencies
npm install

# Start development server
npm start

# Build and copy to server resources
npm run build-run
```

## Architecture

### Core Layer Architecture

The codebase follows a clear separation of concerns across three main layers:

1. **Driver Layer** (`maestro-client/src/main/java/maestro/Driver.kt`)
   - Interface defining platform-specific device interactions
   - Implementations: `AndroidDriver`, `IOSDriver`, `WebDriver` (CDP-based), `FakeDriver` (testing)
   - Handles low-level device operations: tap, swipe, input text, screenshots, etc.
   - Must remain completely platform-agnostic in the interface

2. **Maestro Layer** (`maestro-client/src/main/java/maestro/Maestro.kt`)
   - Target-agnostic API wrapping the Driver
   - Provides higher-level operations with retry logic, waiting, and settling
   - Examples: `launchApp()`, `tap()`, `inputText()`, `findElementWithTimeout()`
   - Should NOT know about the concept of commands or YAML files

3. **Orchestra Layer** (`maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt`)
   - Translates high-level Maestro commands (YAML) into `Maestro` API calls
   - Handles command execution, flow control, JavaScript evaluation, retries
   - Manages command metadata, callbacks, and error handling
   - Core execution entry points: `runFlow()`, `executeCommands()`, `executeCommand()`

### Command Architecture

- **Commands** (`maestro-orchestra-models/src/main/java/maestro/orchestra/Commands.kt`)
  - Sealed interface hierarchy defining all available test commands
  - Each command is a data class implementing `Command` interface
  - Examples: `TapOnElementCommand`, `InputTextCommand`, `AssertCommand`, `ScrollUntilVisibleCommand`
  - Commands support JavaScript evaluation via `evaluateScripts()`

- **MaestroCommand** (`maestro-orchestra-models/src/main/java/maestro/orchestra/MaestroCommand.kt`)
  - Wrapper class that holds a single `Command` and metadata
  - JSON-serializable (required for Maestro Cloud execution)
  - Note: NOT a sealed class due to serialization requirements

- **YAML Parsing** (`maestro-orchestra/src/main/java/maestro/orchestra/yaml/`)
  - `YamlCommandReader` parses YAML test files into `MaestroCommand` objects
  - `YamlFluentCommand` maps YAML structure to `MaestroCommand` representation

### Module Structure

- `maestro-cli` - Command-line interface entry point (App.kt), subcommands, test runner
- `maestro-client` - Core Maestro API, Driver interface, platform-specific drivers
- `maestro-orchestra` - Command execution engine, flow control, Orchestra class
- `maestro-orchestra-models` - Command definitions, element selectors, conditions
- `maestro-android` - Android-specific test runner (UIAutomator-based)
- `maestro-ios` - iOS-specific components
- `maestro-ios-driver` - iOS driver implementation
- `maestro-ios-xctest-runner` - iOS XCTest runner app (Swift)
- `maestro-test` - Integration tests using FakeDriver
- `maestro-utils` - Shared utilities
- `maestro-ai` - AI-powered commands (OpenAI, Anthropic)
- `maestro-web` - Web driver implementation
- `maestro-studio` - Interactive Studio UI (React frontend + server)

### CLI Architecture

- Entry point: `maestro-cli/src/main/java/maestro/cli/App.kt`
- Uses Picocli for command parsing
- Main commands defined as separate classes in `maestro-cli/src/main/java/maestro/cli/command/`:
  - `TestCommand` - Run Maestro tests
  - `StudioCommand` - Launch interactive Studio
  - `CloudCommand` - Upload and manage cloud tests
  - `RecordCommand` - Record user interactions
  - Plus: QueryCommand, PrintHierarchyCommand, StartDeviceCommand, etc.

## Key Architectural Rules

When contributing to Maestro, follow these principles:

1. **Platform Agnosticism**
   - `Maestro` class must remain target-agnostic (no Android/iOS/Web-specific logic)
   - `Orchestra` class must remain platform-agnostic
   - Use `Driver` interface for platform-specific functionality
   - Commands should be as platform-agnostic as possible (exceptions allowed when justified)

2. **Command Execution Flow**
   - `Maestro` should NOT know about commands or YAML
   - `Orchestra` translates commands → `Maestro` API → `Driver` implementation
   - JavaScript engine integration happens at Orchestra level

3. **Cross-Platform Compatibility**
   - CLI must work on macOS, Linux, and Windows
   - Code should not spawn arbitrary processes (sandboxed cloud environment requirement)
   - No bash script execution from Maestro commands (security requirement)

4. **JSON Serialization**
   - `MaestroCommand` must remain JSON-serializable for Maestro Cloud
   - This is why it's not a sealed class despite seeming like a good fit

5. **Testing Philosophy**
   - Do NOT use mocks - use fakes instead (e.g., `FakeDriver`)
   - Integration tests use real component implementations except Driver
   - Manual testing should use local build: `./maestro` instead of global `maestro`

## Java Version Requirements

- **Deployment target**: Java 8 (maintain compatibility - many users still use Java 8)
- **Development**: Java 11 or newer required
- **Build**: Configured for Java 17 (see root build.gradle.kts)

When building CLI changes:
```bash
./gradlew :maestro-cli:installDist
./maestro-cli/build/install/maestro/bin/maestro <command>
```

## iOS Development Notes

- iOS XCTest runner is built with Xcode version from GitHub Actions macos runner
- Check [macos runner readme](https://github.com/actions/runner-images/blob/main/images/macos/macos-14-Readme.md) for default Xcode version
- If local changes work but CI fails, check for newer Swift/Xcode features

## Log Locations

Maestro stores logs for debugging:
- CLI logs: `~/.maestro/tests/*/maestro.log`
- iOS test runner logs: `~/Library/Logs/maestro/xctest_runner_logs`

## Adding a New Command

To add a new command to Maestro:

1. Define command in `Commands.kt` implementing `Command` interface
2. Add field to `MaestroCommand` class following existing pattern
3. Add field to `YamlFluentCommand` for YAML ↔ MaestroCommand mapping
4. Handle command execution in `Orchestra.executeCommand()` method
5. If new functionality needed, add methods to `Maestro` and `Driver` APIs
6. Add integration test to `IntegrationTest` in maestro-test module

## Video Recording Functionality

### Overview

The recording functionality captures video recordings of flow executions during test runs. **Recording only happens on the last retry attempt** to avoid wasting resources on tests that will be retried. Recordings are uploaded to Google Cloud Storage **only when tests fail** - passed tests never have recordings uploaded. Local recordings are always deleted after processing.

### How It Works

1. **Recording decision** is made at the start of `TestSuiteInteractor.runFlow()`:
   - If `ATTEMPT_NUMBER < MAX_RETRIES`: Skip recording (test will be retried if it fails)
   - If `ATTEMPT_NUMBER >= MAX_RETRIES`: Start recording (this is the last attempt)
2. The `Maestro.startScreenRecording()` method delegates to platform-specific drivers:
   - `AndroidDriver` - Uses device screen recording capabilities
   - `IOSDriver` - Uses iOS screen recording via XCTest/simctl
   - `WebDriver`/`CdpWebDriver` - Web-specific recording
3. Video is streamed to a temporary local file during flow execution
4. **Recording stops** when the flow completes (success or failure)
5. **Upload decision**:
   - If test **passes**: Delete local recording (no upload needed)
   - If test **fails**: Upload to GCS, output `[RECORDING]` URL, then delete local recording
6. **Local cleanup**: Local recording file is always deleted after processing

### Recording Naming Convention

Recordings are uploaded to the **root of the GCS bucket** with a structured filename:

```
{buildName}-{buildNumber}-{deviceName}-{attemptNumber}-{flowName}.mp4
```

Example: `nightly-12345-OmioIOS1-3-login_flow.mp4`

### CLI Options

```bash
# Disable recording (recording is enabled by default)
./maestro test --no-record flow.yaml

# Specify GCS bucket for upload
./maestro test --gcs-bucket my-bucket flow.yaml

# Specify attempt number (for conditional recording on last attempt)
./maestro test --attempt-number 3 --max-retries 3 flow.yaml
```

### Environment Variables

| Variable | Description |
|----------|-------------|
| `GCS_BUCKET` | Bucket name for GCS uploads |
| `GOOGLE_APPLICATION_CREDENTIALS` | Path to service account JSON key file for GCS authentication |
| `BUILD_NAME` | Build identifier for recording naming (default: "local"). Pipeline derives from nightly/pr flags |
| `BUILD_NUMBER` | CI build number for recording naming (default: "0") |
| `DEVICE_NAME` | Simulator/device name for recording naming (e.g., "OmioIOS1") |
| `ATTEMPT_NUMBER` | Current retry attempt number, 1-based (default: "1") |
| `MAX_RETRIES` | Total number of attempts allowed (default: "1"). Recording only happens when `ATTEMPT_NUMBER >= MAX_RETRIES` |

### File Locations

- **Local recordings**: Temporary file at `<test-output-dir>/recordings/{flowName}.mp4` (deleted after processing)
- **GCS location**: `gs://{bucket}/{buildName}-{buildNumber}-{deviceName}-{attemptNumber}-{flowName}.mp4`

### Implementation Details

Key files:
- `maestro-cli/src/main/java/maestro/cli/command/TestCommand.kt:226-248` - CLI options (`--no-record`, `--gcs-bucket`, `--attempt-number`, `--max-retries`)
- `maestro-cli/src/main/java/maestro/cli/runner/TestSuiteInteractor.kt:188-332` - Recording lifecycle (conditional start, upload on failure, cleanup)
- `maestro-cli/src/main/java/maestro/cli/util/GcsUploader.kt` - GCS upload with naming convention
- `maestro-client/src/main/java/maestro/Maestro.kt` - Screen recording wrapper
- `maestro-client/src/main/java/maestro/Driver.kt` - Driver interface method

### External Pipeline Integration

The external pipeline (`tools/maestro-pipelines/utils.ts`) sets the environment variables in `maestroRunTests()`:
```typescript
const buildName = nightly ? 'nightly' : pr ? 'pr-check' : 'local';
const buildNumber = process.env.BUILD_NUMBER || process.env.BUILD_ID || '0';

const recordingEnv: Record<string, string> = {
  ATTEMPT_NUMBER: String((ctx.retryCount ?? 0) + 1),
  MAX_RETRIES: String(maxRetry + 1),
  BUILD_NAME: buildName,
  BUILD_NUMBER: buildNumber,
  DEVICE_NAME: deviceName,  // From ctx.deviceNames
  GCS_BUCKET: process.env.GCS_BUCKET || '',
  GOOGLE_APPLICATION_CREDENTIALS: process.env.GOOGLE_APPLICATION_CREDENTIALS || '',
};
```

The `spawn.ts` module passes these env vars to the spawned Maestro process.

### Recording URL Tracking in TestResultsMap

The `TestingStatus` type includes a `recordingUrl` field to track uploaded recordings:

```typescript
export type TestingStatus<S extends RunningStatus> = {
  status: S;
  package: string;
  fullPath: string;
  included: boolean;
  duration?: string;
  error?: S extends 'failed' ? string : never;
  recordingUrl?: string; // GCS URL (only set for failed tests on last attempt)
};
```

Maestro outputs recording URLs in a parseable format:
```
[RECORDING] flowName https://storage.googleapis.com/bucket/nightly-12345-OmioIOS1-3-flowName.mp4
```

The pipeline parses this using `recordingMatchingRegex` in `convertOutputToTestExecutionMap()`:
```typescript
export const recordingMatchingRegex = /\[RECORDING\]\s+(\S+)\s+(https:\/\/[^\s]+)/gm;
```

### What's Still Missing / TODO

1. **Recording URL in test reports**: The GCS URL is printed to console but not included in JUnit/HTML test reports
2. **S3/Azure support**: Only GCS is implemented; could add AWS S3 or Azure Blob Storage
3. **Compression options**: No configurable video quality/compression settings
4. **Integration with Maestro Cloud**: Recording upload is separate from Maestro Cloud workflow
5. **Web driver recording quality**: Web drivers may have limitations compared to mobile recording

### Example Usage

```bash
# Nightly E2E with GCS upload (only failed recordings on last attempt)
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
export GCS_BUCKET=my-test-recordings
export BUILD_NAME=nightly
export BUILD_NUMBER=12345
export DEVICE_NAME=OmioIOS1
export ATTEMPT_NUMBER=3
export MAX_RETRIES=3
./maestro test flows/

# Disable recording for quick test runs
./maestro test --no-record flows/

# Override bucket for specific run
./maestro test --gcs-bucket staging-recordings flows/
```

## Important Notes

- Tests are written in YAML and stored in `maestro-test/src/test/resources/`
- E2E tests and sample flows in `e2e/` directory
- Tests automatically wait for UI to settle (no manual sleep() needed)
- Built-in flakiness tolerance - elements may not always be where expected
- Framework handles network delays and loading automatically

## Fork Documentation Requirements

**IMPORTANT:** This repository is a fork of [mobile-dev-inc/Maestro](https://github.com/mobile-dev-inc/Maestro). All changes from upstream must be documented.

### Updating the Fork Changelog

After every commit that introduces changes (not sync/merge commits), you **MUST** update the `README.md` Fork Changelog section:

1. Add the commit hash and message under the appropriate category:
   - **Added** - New features, commands, CLI options
   - **Changed** - Modifications to existing behavior, build changes
   - **Fixed** - Bug fixes, stability improvements
   - **Removed** - Deleted features or code

2. Include a brief description of what the code actually does (not just the commit message)

3. Group related commits together under descriptive subheadings

Example entry:
```markdown
#### Feature Name

Brief description of what this feature does.

**Commits:**
- `abc12345` commit message here
- `def67890` another related commit
```

This ensures we maintain clear documentation of all differences from upstream for future reference and potential contribution back to the main repository.
