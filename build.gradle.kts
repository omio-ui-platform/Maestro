import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.detekt)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.named("compileKotlin", KotlinCompilationTask::class.java) {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjdk-release=17")
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = true
    config = files("${rootDir}/detekt.yml")
}

// Keep the Kotlin stdlib/reflect family on a single, consistent version at runtime.
//
// Two transitive forces pull it apart: dev.mobile:dadb:2.0.0 declares kotlin-bom:1.9.20, which
// pins kotlin-reflect (and the kotlin-stdlib-jdk7/jdk8 shims) to 1.9.20, while
// io.modelcontextprotocol:kotlin-sdk forces kotlin-stdlib UP to 2.3.10. The resulting stale
// kotlin-reflect (1.9.20) cannot read the 2.x-compiled Kotlin @Metadata, so jackson-module-kotlin
// silently fails to introspect reserved-word properties (e.g. YamlCondition.`true`) — which makes
// any flow using a `repeat: { while: { true: ${...} } }` condition fail with "Parsing Failed".
//
// Align the whole family UP to the stdlib version that's already resolving in (2.3.10, required by
// the MCP SDK). This only moves the stale artifacts (reflect + jdk7/jdk8 shims) up to match the
// stdlib; it does not downgrade anything currently on the classpath. Analogous to the grpc
// alignment (see gradle/libs.versions.toml grpc = "1.59.1"). Bump this in lockstep if a future
// upstream sync raises kotlin-stdlib past 2.3.10.
val alignedKotlinRuntimeVersion = "2.3.10"
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (
                requested.group == "org.jetbrains.kotlin" &&
                (requested.name.startsWith("kotlin-stdlib") || requested.name == "kotlin-reflect")
            ) {
                useVersion(alignedKotlinRuntimeVersion)
                because("align kotlin-reflect with kotlin-stdlib so Jackson can read Kotlin @Metadata")
            }
        }
    }
}
