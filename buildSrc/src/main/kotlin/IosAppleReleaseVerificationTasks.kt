import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.register

data class IosAppleReleaseVerificationTasks(
    val verifyCodexAgentRemoteSwiftPackage: TaskProvider<VerifySwiftPackageBinaryTask>,
    val verifyIosDeploymentTargets: TaskProvider<Exec>,
    val verifyIosPrivacyManifest: TaskProvider<VerifyPrivacyRequiredReasonTask>,
    val verifyIosReleaseBudgets: TaskProvider<Exec>,
)

fun Project.registerAppleToolchainVerificationTask(
    expectedXcodeVersion: String,
    expectedXcodeBuild: String,
    expectedSwiftVersion: String,
) = tasks.register<Exec>("verifyAppleToolchain") {
    val reportDirectory = layout.buildDirectory.dir("reports/ios-release/toolchain")
    outputs.dir(reportDirectory)
    commandLine(
        "/bin/bash",
        "-c",
        """
            set -euo pipefail
            mkdir -p "${reportDirectory.get().asFile.absolutePath}"
            xcodebuild -version | tee "${reportDirectory.get().file("xcode.txt").asFile.absolutePath}"
            swift --version | tee "${reportDirectory.get().file("swift.txt").asFile.absolutePath}"
            grep -Fx 'Xcode $expectedXcodeVersion' "${reportDirectory.get().file("xcode.txt").asFile.absolutePath}"
            grep -Fx 'Build version $expectedXcodeBuild' "${reportDirectory.get().file("xcode.txt").asFile.absolutePath}"
            grep -F 'Apple Swift version $expectedSwiftVersion' "${reportDirectory.get().file("swift.txt").asFile.absolutePath}"
        """.trimIndent(),
    )
}

fun Project.registerIosAppleReleaseVerificationTasks(
    distribution: IosAppleDistributionTasks,
    minimumIosVersion: String,
): IosAppleReleaseVerificationTasks {
    val swiftPackageArchiveName = "CodexAgent-${project.version}.xcframework.zip"
    val packageCodexAgentSwiftPackageBinary = tasks.register<Zip>("packageCodexAgentSwiftPackageBinary") {
        dependsOn(distribution.prepareCodexAgentReleaseXCFramework)
        archiveFileName.set(swiftPackageArchiveName)
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(distribution.releaseXCFrameworkDirectory) { into("CodexAgent.xcframework") }
    }

    val verifyIosDeploymentTargets = tasks.register<Exec>("verifyIosDeploymentTargets") {
        dependsOn(distribution.prepareCodexAgentReleaseXCFramework)
        val report = layout.buildDirectory.file("reports/ios-release/deployment-targets.txt")
        outputs.file(report)
        commandLine(
            "/bin/bash",
            "-c",
            """
                set -euo pipefail
                mkdir -p "${report.get().asFile.parentFile.absolutePath}"
                : > "${report.get().asFile.absolutePath}"
                records_file=${'$'}(mktemp)
                trap 'rm -f "${'$'}records_file"' EXIT
                count=0
                for binary in "${distribution.releaseXCFrameworkDirectory.get().asFile.absolutePath}"/*/CodexAgent.framework/CodexAgent; do
                    count=${'$'}((count + 1))
                    echo "== ${'$'}binary ==" | tee -a "${report.get().asFile.absolutePath}"
                    case "${'$'}binary" in
                        */ios-arm64-simulator/*) expected_platform=7 ;;
                        */ios-arm64/*) expected_platform=2 ;;
                        *) exit 1 ;;
                    esac
                    xcrun otool -l "${'$'}binary" | awk '
                        /^[^[:space:]].*\):${'$'}/ {
                            member = ${'$'}0
                            sub(/^.*\(/, "", member)
                            sub(/\):${'$'}/, "", member)
                        }
                        ${'$'}1 == "cmd" && ${'$'}2 == "LC_BUILD_VERSION" { build = 1; next }
                        build && ${'$'}1 == "platform" { platform = ${'$'}2; next }
                        build && ${'$'}1 == "minos" { print member "|" platform ":" ${'$'}2; build = 0 }
                    ' > "${'$'}records_file"
                    test -s "${'$'}records_file"
                    cut -d'|' -f2 "${'$'}records_file" | sort -u | tee -a "${report.get().asFile.absolutePath}"
                    grep -Eq '^CodexAgent\.framework\.o\|[27]:$minimumIosVersion${'$'}' "${'$'}records_file"
                    awk -F'[|:]' -v platform="${'$'}expected_platform" '
                        ${'$'}2 != platform { exit 1 }
                        ${'$'}3 == "15.0" { next }
                        ${'$'}3 == "14.0" && ${'$'}1 ~ /^(std|panic_unwind|object|memchr|addr2line|gimli|cfg_if|rustc_demangle|std_detect|hashbrown|rustc_std_workspace_alloc|miniz_oxide|adler2|unwind|libc|rustc_std_workspace_core|alloc|core|compiler_builtins)-/ { next }
                        ${'$'}3 == "14.0" && ${'$'}1 ~ /^(ad3ac4dcdcbf93cb|b6006474dd997b0d|f3c5cc7ab326d4d0)-/ { next }
                        { print "Unexpected deployment target: " ${'$'}0 > "/dev/stderr"; exit 1 }
                    ' "${'$'}records_file"
                    /usr/libexec/PlistBuddy -c 'Print :MinimumOSVersion' "${'$'}{binary%/CodexAgent}/Info.plist" | grep -Fx '$minimumIosVersion'
                done
                test "${'$'}count" -eq 2
                grep -q '^2:$minimumIosVersion${'$'}' "${report.get().asFile.absolutePath}"
                grep -q '^7:$minimumIosVersion${'$'}' "${report.get().asFile.absolutePath}"
            """.trimIndent(),
        )
    }

    val collectIosPrivacyEvidence = tasks.register<Exec>("collectIosPrivacyEvidence") {
        dependsOn(distribution.prepareCodexAgentReleaseXCFramework, distribution.verifyCodexAgentSwiftPackage)
        val reportDirectory = layout.buildDirectory.dir("reports/ios-release/privacy")
        val symbols = reportDirectory.map { it.file("undefined-symbols.txt") }
        val packaging = reportDirectory.map { it.file("packaging.json") }
        outputs.files(symbols, packaging)
        commandLine(
            "/bin/bash",
            "-c",
            """
                set -euo pipefail
                report="${reportDirectory.get().asFile.absolutePath}"
                mkdir -p "${'$'}report"
                /usr/bin/plutil -lint "${distribution.privacyManifestFile.asFile.absolutePath}" | tee "${'$'}report/manifest-lint.txt"
                framework_count=${'$'}(find "${distribution.releaseXCFrameworkDirectory.get().asFile.absolutePath}" -name PrivacyInfo.xcprivacy -type f | wc -l | tr -d ' ')
                test "${'$'}framework_count" -eq 2
                archive="${layout.buildDirectory.file("CodexAgentTestApp.xcarchive").get().asFile.absolutePath}"
                app_count=${'$'}(find "${'$'}archive/Products/Applications" -name PrivacyInfo.xcprivacy -type f | wc -l | tr -d ' ')
                test "${'$'}app_count" -ge 1
                binary="${'$'}archive/Products/Applications/CodexAgentTestApp.app/CodexAgentTestApp"
                nm -u "${'$'}binary" > "${'$'}report/undefined-symbols.txt"
                ! grep -Eq '_(statfs|fstatfs)${'$'}' "${'$'}report/undefined-symbols.txt"
                printf '{"frameworkManifests":%s,"archivedAppManifests":%s}\n' \
                    "${'$'}framework_count" "${'$'}app_count" > "${'$'}report/packaging.json"
            """.trimIndent(),
        )
    }

    val verifyIosPrivacyManifest = tasks.register<VerifyPrivacyRequiredReasonTask>("verifyIosPrivacyManifest") {
        dependsOn(collectIosPrivacyEvidence)
        privacyManifest.set(distribution.privacyManifestFile)
        undefinedSymbols.set(layout.buildDirectory.file("reports/ios-release/privacy/undefined-symbols.txt"))
        reviewsFile.set(rootProject.layout.projectDirectory.file("release/privacy-required-reason-reviews-0.2.0.json"))
        packagingEvidence.set(layout.buildDirectory.file("reports/ios-release/privacy/packaging.json"))
        auditFile.set(layout.buildDirectory.file("reports/ios-release/privacy/audit.json"))
    }

    val verifyIosReleaseBudgets = tasks.register<Exec>("verifyIosReleaseBudgets") {
        dependsOn(packageCodexAgentSwiftPackageBinary, distribution.verifyCodexAgentSwiftPackage)
        val budgets = rootProject.layout.projectDirectory.file("release/ios-budgets-0.2.0.json")
        val report = layout.buildDirectory.file("reports/ios-release/artifact-metrics.json")
        inputs.file(budgets)
        inputs.file(packageCodexAgentSwiftPackageBinary.flatMap { it.archiveFile })
        inputs.dir(distribution.releaseXCFrameworkDirectory)
        inputs.dir(layout.buildDirectory.dir("CodexAgentTestApp.xcarchive"))
        outputs.file(report)
        commandLine(
            "/bin/bash",
            "-c",
            """
                set -euo pipefail
                mkdir -p "${report.get().asFile.parentFile.absolutePath}"
                zip_bytes=${'$'}(stat -f %z "${packageCodexAgentSwiftPackageBinary.get().archiveFile.get().asFile.absolutePath}")
                device_bytes=${'$'}(stat -f %z "${distribution.releaseXCFrameworkDirectory.get().dir("ios-arm64/CodexAgent.framework").file("CodexAgent").asFile.absolutePath}")
                app="${layout.buildDirectory.file("CodexAgentTestApp.xcarchive/Products/Applications/CodexAgentTestApp.app").get().asFile.absolutePath}"
                app_bytes=${'$'}(find "${'$'}app" -type f -exec stat -f %z {} + | awk '{ total += ${'$'}1 } END { print total + 0 }')
                test "${'$'}zip_bytes" -le ${'$'}(jq -er '.artifactBytes.compressedXcframeworkMaximum' "${budgets.asFile.absolutePath}")
                test "${'$'}device_bytes" -le ${'$'}(jq -er '.artifactBytes.deviceFrameworkMaximum' "${budgets.asFile.absolutePath}")
                test "${'$'}app_bytes" -le ${'$'}(jq -er '.artifactBytes.sampleAppInstallMaximum' "${budgets.asFile.absolutePath}")
                printf '{"compressedXcframeworkBytes":%s,"deviceFrameworkBytes":%s,"sampleAppInstallBytes":%s}\n' \
                    "${'$'}zip_bytes" "${'$'}device_bytes" "${'$'}app_bytes" > "${report.get().asFile.absolutePath}"
            """.trimIndent(),
        )
    }

    val swiftPackageChecksumFile = layout.buildDirectory.file("distributions/$swiftPackageArchiveName.sha256")
    val generateCodexAgentSwiftPackageChecksum =
        tasks.register<GenerateSha256Task>("generateCodexAgentSwiftPackageChecksum") {
            dependsOn(packageCodexAgentSwiftPackageBinary)
            inputFile.set(packageCodexAgentSwiftPackageBinary.flatMap { it.archiveFile })
            outputFile.set(swiftPackageChecksumFile)
        }

    tasks.register<VerifySwiftPackageReproducibilityTask>("verifyCodexAgentSwiftPackageReproducibility") {
        manifest.set(rootProject.layout.projectDirectory.file("Package.swift"))
        gradleWrapper.set(rootProject.layout.projectDirectory.file("gradlew"))
        repositoryDirectory.set(rootProject.layout.projectDirectory)
        archiveFile.set(layout.buildDirectory.file("distributions/$swiftPackageArchiveName"))
    }

    val verifyCodexAgentRemoteSwiftPackage =
        tasks.register<VerifySwiftPackageBinaryTask>("verifyCodexAgentRemoteSwiftPackage") {
            group = "verification"
            description = "Verifies the public SwiftPM manifest URL and binary checksum."
            dependsOn(generateCodexAgentSwiftPackageChecksum)
            manifest.set(rootProject.layout.projectDirectory.file("Package.swift"))
            checksumFile.set(swiftPackageChecksumFile)
            expectedUrl.set(
                "https://github.com/ciurlaro/codex-agent/releases/download/v${project.version}/$swiftPackageArchiveName",
            )
        }

    return IosAppleReleaseVerificationTasks(
        verifyCodexAgentRemoteSwiftPackage = verifyCodexAgentRemoteSwiftPackage,
        verifyIosDeploymentTargets = verifyIosDeploymentTargets,
        verifyIosPrivacyManifest = verifyIosPrivacyManifest,
        verifyIosReleaseBudgets = verifyIosReleaseBudgets,
    )
}
