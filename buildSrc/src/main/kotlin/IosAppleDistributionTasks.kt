import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.register

data class IosAppleDistributionTasks(
    val appleDistributionDirectory: Provider<Directory>,
    val releaseXCFrameworkDirectory: Provider<Directory>,
    val privacyManifestFile: RegularFile,
    val prepareCodexAgentReleaseXCFramework: TaskProvider<Exec>,
    val packageCodexAgentAppleDistribution: TaskProvider<Zip>,
    val verifyCodexAgentSwiftPackage: TaskProvider<Exec>,
    val verifyCodexAgentSwiftAuthenticationTests: TaskProvider<Exec>,
    val verifyIosLicensePackaging: TaskProvider<Exec>,
)

fun Project.registerIosAppleDistributionTasks(expectedSwiftTestCount: Int): IosAppleDistributionTasks {
    val appleDistributionDirectory = layout.buildDirectory.dir("apple-distribution")
    val assembledXCFrameworkDirectory = layout.buildDirectory.dir("XCFrameworks/release/CodexAgent.xcframework")
    val releaseXCFrameworkDirectory = layout.buildDirectory.dir("release-xcframework/CodexAgent.xcframework")
    val privacyManifestFile =
        layout.projectDirectory.file("apple/Sources/CodexAgentAuthentication/PrivacyInfo.xcprivacy")

    val prepareCodexAgentReleaseXCFramework = tasks.register<Exec>("prepareCodexAgentReleaseXCFramework") {
        dependsOn("assembleCodexAgentReleaseXCFramework")
        inputs.dir(assembledXCFrameworkDirectory)
        inputs.file(privacyManifestFile)
        outputs.dir(releaseXCFrameworkDirectory)
        doFirst { releaseXCFrameworkDirectory.get().asFile.deleteRecursively() }
        commandLine(
            "/bin/bash",
            "-c",
            """
                set -euo pipefail
                mkdir -p "${releaseXCFrameworkDirectory.get().asFile.parentFile.absolutePath}"
                /bin/cp -cR \
                    "${assembledXCFrameworkDirectory.get().asFile.absolutePath}" \
                    "${releaseXCFrameworkDirectory.get().asFile.absolutePath}"
                for slice in ios-arm64 ios-arm64-simulator; do
                    /bin/cp \
                        "${privacyManifestFile.asFile.absolutePath}" \
                        "${releaseXCFrameworkDirectory.get().asFile.absolutePath}/${'$'}slice/CodexAgent.framework/PrivacyInfo.xcprivacy"
                    archive="${releaseXCFrameworkDirectory.get().asFile.absolutePath}/${'$'}slice/CodexAgent.framework/CodexAgent"
                    normalized="${'$'}archive.normalized"
                    rm -f "${'$'}normalized"
                    /usr/bin/xcrun libtool -static -D -no_warning_for_no_symbols \
                        "${'$'}archive" -o "${'$'}normalized"
                    /bin/mv "${'$'}normalized" "${'$'}archive"
                done
                info_plist="${releaseXCFrameworkDirectory.get().asFile.absolutePath}/Info.plist"
                sorted=${'$'}(/usr/bin/plutil -extract AvailableLibraries json -o - "${'$'}info_plist" | /usr/bin/env jq -c 'sort_by(.LibraryIdentifier)')
                /usr/bin/plutil -replace AvailableLibraries -json "${'$'}sorted" "${'$'}info_plist"
            """.trimIndent(),
        )
    }

    val stageCodexAgentAppleDistribution = tasks.register<Exec>("stageCodexAgentAppleDistribution") {
        dependsOn(prepareCodexAgentReleaseXCFramework)
        inputs.files(
            layout.projectDirectory.file("apple/Package.swift"),
            layout.projectDirectory.dir("apple/Sources"),
            layout.projectDirectory.dir("apple/Tests"),
            releaseXCFrameworkDirectory,
            rootProject.layout.projectDirectory.file("LICENSE"),
            rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"),
            rootProject.layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-LICENSE.txt"),
            rootProject.layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-NOTICE.txt"),
            layout.projectDirectory.dir("apple/TestApp"),
        )
        outputs.dir(appleDistributionDirectory)
        doFirst { appleDistributionDirectory.get().asFile.deleteRecursively() }
        commandLine(
            "/bin/bash",
            "-c",
            """
                set -euo pipefail
                distribution="${appleDistributionDirectory.get().asFile.absolutePath}"
                package="${appleDistributionDirectory.get().dir("CodexAgentPackage").asFile.absolutePath}"
                mkdir -p "${'$'}package"
                /bin/cp "${layout.projectDirectory.file("apple/Package.swift").asFile.absolutePath}" "${'$'}package/"
                /bin/cp -R "${layout.projectDirectory.dir("apple/Sources").asFile.absolutePath}" "${'$'}package/Sources"
                /bin/cp -R "${layout.projectDirectory.dir("apple/Tests").asFile.absolutePath}" "${'$'}package/Tests"
                /bin/cp -cR "${releaseXCFrameworkDirectory.get().asFile.absolutePath}" "${'$'}package/CodexAgent.xcframework"
                /bin/cp "${rootProject.layout.projectDirectory.file("LICENSE").asFile.absolutePath}" "${'$'}package/LICENSE.txt"
                /bin/cp "${rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md").asFile.absolutePath}" "${'$'}package/"
                /bin/cp "${rootProject.layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-LICENSE.txt").asFile.absolutePath}" "${'$'}package/"
                /bin/cp "${rootProject.layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-NOTICE.txt").asFile.absolutePath}" "${'$'}package/"
                /bin/cp -R "${layout.projectDirectory.dir("apple/TestApp").asFile.absolutePath}" "${'$'}distribution/CodexAgentTestApp"
            """.trimIndent(),
        )
    }

    val verifyCodexAgentSwiftPackage = tasks.register<Exec>("verifyCodexAgentSwiftPackage") {
        dependsOn(stageCodexAgentAppleDistribution)
        workingDir(appleDistributionDirectory.map { it.dir("CodexAgentTestApp") })
        commandLine(
            "xcodebuild",
            "-project", "CodexAgentTestApp.xcodeproj",
            "-scheme", "CodexAgentTestApp",
            "-configuration", "Release",
            "-destination", "generic/platform=iOS",
            "-derivedDataPath", layout.buildDirectory.dir("swift-consumer-derived-data").get().asFile.absolutePath,
            "-archivePath", layout.buildDirectory.file("CodexAgentTestApp.xcarchive").get().asFile.absolutePath,
            "ARCHS=arm64",
            "CODE_SIGNING_ALLOWED=NO",
            "SKIP_INSTALL=NO",
            "clean",
            "archive",
        )
    }

    val verifyCodexAgentSwiftAuthenticationTests =
        tasks.register<Exec>("verifyCodexAgentSwiftAuthenticationTests") {
            dependsOn(stageCodexAgentAppleDistribution)
            workingDir(appleDistributionDirectory.map { it.dir("CodexAgentPackage") })
            commandLine(
                "/bin/bash",
                "-c",
                """
                    set -euo pipefail
                    runtime_id=${'$'}(xcrun simctl list -j runtimes | jq -er '.runtimes[] | select(.name == "iOS 26.5" and .isAvailable == true) | .identifier' | head -n 1)
                    device_type='com.apple.CoreSimulator.SimDeviceType.iPhone-17'
                    devices_json="${layout.buildDirectory.file("simulator-devices.json").get().asFile.absolutePath}"
                    xcrun simctl list -j devices available > "${'$'}devices_json"
                    destination_id=${'$'}(jq -er --arg runtime "${'$'}runtime_id" --arg type "${'$'}device_type" '.devices[${'$'}runtime][] | select(.isAvailable == true and .deviceTypeIdentifier == ${'$'}type) | .udid' "${'$'}devices_json" | head -n 1)
                    test -n "${'$'}destination_id"
                    state=${'$'}(jq -er --arg runtime "${'$'}runtime_id" --arg id "${'$'}destination_id" '.devices[${'$'}runtime][] | select(.udid == ${'$'}id) | .state' "${'$'}devices_json")
                    if [ "${'$'}state" != Booted ]; then xcrun simctl boot "${'$'}destination_id"; fi
                    xcrun simctl bootstatus "${'$'}destination_id" -b
                    result_bundle="${layout.buildDirectory.file("swift-authentication-tests.xcresult").get().asFile.absolutePath}"
                    summary="${layout.buildDirectory.file("swift-authentication-tests-summary.json").get().asFile.absolutePath}"
                    rm -rf "${'$'}result_bundle"
                    xcodebuild \
                        -scheme CodexAgent-Package \
                        -destination "platform=iOS Simulator,id=${'$'}destination_id" \
                        -derivedDataPath "${layout.buildDirectory.dir("swift-tests-derived-data").get().asFile.absolutePath}" \
                        -resultBundlePath "${'$'}result_bundle" \
                        CODE_SIGNING_ALLOWED=NO \
                        test
                    xcrun xcresulttool get test-results summary --path "${'$'}result_bundle" --format json > "${'$'}summary"
                    executed=${'$'}(jq -er '.totalTestCount' "${'$'}summary")
                    failed=${'$'}(jq -er '.failedTests // 0' "${'$'}summary")
                    test "${'$'}executed" -eq $expectedSwiftTestCount
                    test "${'$'}failed" -eq 0
                    echo "Swift authentication tests executed: ${'$'}executed"
                """.trimIndent(),
            )
        }

    val packageCodexAgentAppleDistribution = tasks.register<Zip>("packageCodexAgentAppleDistribution") {
        dependsOn(stageCodexAgentAppleDistribution)
        archiveFileName.set("CodexAgentPackage-${project.version}.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(appleDistributionDirectory.map { it.dir("CodexAgentPackage") })
    }

    val verifyIosLicensePackaging = tasks.register<Exec>("verifyIosLicensePackaging") {
        dependsOn(stageCodexAgentAppleDistribution)
        val packageDirectory = appleDistributionDirectory.map { it.dir("CodexAgentPackage") }
        val report = layout.buildDirectory.file("reports/ios-release/license-packaging.txt")
        inputs.files(
            rootProject.layout.projectDirectory.file("LICENSE"),
            rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"),
            rootProject.layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-LICENSE.txt"),
            rootProject.layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-NOTICE.txt"),
        )
        outputs.file(report)
        commandLine(
            "/bin/bash",
            "-c",
            """
                set -euo pipefail
                package="${packageDirectory.get().asFile.absolutePath}"
                cmp "${rootProject.layout.projectDirectory.file("LICENSE").asFile.absolutePath}" "${'$'}package/LICENSE.txt"
                cmp "${rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md").asFile.absolutePath}" "${'$'}package/THIRD_PARTY_NOTICES.md"
                cmp "${rootProject.layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-LICENSE.txt").asFile.absolutePath}" "${'$'}package/openai-codex-LICENSE.txt"
                cmp "${rootProject.layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-NOTICE.txt").asFile.absolutePath}" "${'$'}package/openai-codex-NOTICE.txt"
                grep -F 'GNU General Public License v3.0 or later' "${layout.projectDirectory.file("build.gradle.kts").asFile.absolutePath}"
                mkdir -p "${report.get().asFile.parentFile.absolutePath}"
                shasum -a 256 \
                    "${'$'}package/LICENSE.txt" \
                    "${'$'}package/THIRD_PARTY_NOTICES.md" \
                    "${'$'}package/openai-codex-LICENSE.txt" \
                    "${'$'}package/openai-codex-NOTICE.txt" > "${report.get().asFile.absolutePath}"
            """.trimIndent(),
        )
    }

    return IosAppleDistributionTasks(
        appleDistributionDirectory = appleDistributionDirectory,
        releaseXCFrameworkDirectory = releaseXCFrameworkDirectory,
        privacyManifestFile = privacyManifestFile,
        prepareCodexAgentReleaseXCFramework = prepareCodexAgentReleaseXCFramework,
        packageCodexAgentAppleDistribution = packageCodexAgentAppleDistribution,
        verifyCodexAgentSwiftPackage = verifyCodexAgentSwiftPackage,
        verifyCodexAgentSwiftAuthenticationTests = verifyCodexAgentSwiftAuthenticationTests,
        verifyIosLicensePackaging = verifyIosLicensePackaging,
    )
}
