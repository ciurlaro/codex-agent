import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.artifacts.ExternalModuleDependency
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
    id("codexagent.codex-runtime")
}

val bundledSqliteTest = dependencies.create(libs.androidx.sqlite.bundled.get()) as ExternalModuleDependency
bundledSqliteTest.attributes {
    attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
}

extensions.configure<LibraryExtension> {
    namespace = "io.github.ciurlaro.codexmobile.agent.runtime.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libcodex_app_server.so"
            useLegacyPackaging = true
        }
    }
}

dependencies {
    api(project(":codex-agent-client"))
    api(libs.androidx.sqlite)
    api(libs.okio)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test-junit"))
    testImplementation(bundledSqliteTest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

val prepareRuntime = tasks.named<PrepareCodexRuntimeTask>("prepareCodexRuntime")
extensions.getByType<LibraryAndroidComponentsExtension>().onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(
        prepareRuntime,
        PrepareCodexRuntimeTask::outputDirectory,
    )
}

val localPropertiesFile = rootProject.layout.projectDirectory.file("local.properties").asFile
val androidSdkPath = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(providers.provider {
        localPropertiesFile.takeIf(File::isFile)?.readLines()
            ?.singleOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter('=')
            ?: error("ANDROID_HOME, ANDROID_SDK_ROOT, or sdk.dir is required")
    })

tasks.register<RecordAndroidRuntimeEvidenceTask>("recordAndroidRuntimeEvidence") {
    group = "verification"
    description = "Runs the exact ARM64 instrumentation smoke and records hash-bound candidate evidence."
    dependsOn("connectedDebugAndroidTest", "assembleRelease")
    candidateCommit.set(providers.gradleProperty("codexAgent.candidateCommit"))
    pinnedRuntimeSha256.set(prepareRuntime.flatMap { it.binarySha256 })
    outputMetadata.set(layout.buildDirectory.file("outputs/apk/androidTest/debug/output-metadata.json"))
    testResults.set(layout.buildDirectory.dir("outputs/androidTest-results/connected/debug"))
    releaseAar.set(layout.buildDirectory.file("outputs/aar/codex-agent-runtime-android-release.aar"))
    adbExecutable.set(layout.file(androidSdkPath.map { file("$it/platform-tools/adb") }))
    apkanalyzerExecutable.set(layout.file(androidSdkPath.map { file("$it/cmdline-tools/latest/bin/apkanalyzer") }))
    repositoryDirectory.set(rootProject.layout.projectDirectory)
    evidenceDirectory.set(layout.buildDirectory.dir("reports/android-runtime-evidence"))
    outputs.upToDateWhen { false }
}

mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        ),
    )
    coordinates("io.github.ciurlaro", "codex-agent-runtime-android", project.version.toString())
    publishToMavenCentral(automaticRelease = true)
    if (
        providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.secretKeyRingFile").isPresent
    ) {
        signAllPublications()
    }
    pom {
        name.set("Codex Agent Runtime for Android")
        description.set("Android process runtime and verified Codex App Server distribution for Codex Agent.")
        inceptionYear.set("2026")
        url.set("https://github.com/ciurlaro/codex-agent")
        licenses {
            license {
                name.set("GNU General Public License v3.0 or later")
                url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("ciurlaro")
                name.set("Cesare Iurlaro")
                url.set("https://github.com/ciurlaro")
            }
        }
        scm {
            url.set("https://github.com/ciurlaro/codex-agent")
            connection.set("scm:git:https://github.com/ciurlaro/codex-agent.git")
            developerConnection.set("scm:git:ssh://git@github.com/ciurlaro/codex-agent.git")
        }
    }
}

dependencyLocking {
    lockAllConfigurations()
}
