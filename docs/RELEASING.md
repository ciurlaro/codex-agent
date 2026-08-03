# Releasing

1. Run the complete Linux/Android/JVM checks and `verifyIosRuntime` on Apple
   Silicon macOS with full Xcode, Rust `1.95.0`, and both Apple Rust targets.
   Supply `OPENAI_API_KEY` so the protected real-model simulator test runs.
2. Run the Android runtime instrumentation smoke test on an ARM64 Android host.
3. Run the standalone Swift test app on a signed physical device. If signing or
   a device is unavailable, record physical execution as the sole unproven Apple
   gate; device compilation/linking must still pass.
4. Set a new immutable version in the root build; never reuse a released version.
5. Build `packageCodexAgentAppleDistribution` and retain
   `codex-agent-runtime-ios/build/distributions/CodexAgentPackage-<version>.zip`
   with the release artifacts. Confirm its XCFramework contains both
   `ios-arm64` and `ios-arm64-simulator` slices and its clean sample app builds.
6. Configure the Maven Central namespace `io.github.ciurlaro` and repository
   secrets `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`,
   `SIGNING_IN_MEMORY_KEY`, `SIGNING_IN_MEMORY_KEY_PASSWORD`, and the protected
   simulator credential `OPENAI_API_KEY`.
7. Create and publish the matching GitHub release tag. The release workflow runs
   `publishAndReleaseToMavenCentral` and waits for Central validation.
8. Resolve all three Maven coordinates from a clean consumer checkout and the
   zipped Swift Package from a clean native Swift project before updating hosts.

For migration-only verification, publish the same fixed version to an isolated
repository:

```shell
./gradlew publishAllPublicationsToMigrationRepository \
  -PcodexAgent.localRepository=/tmp/codex-agent-maven
```

Do not commit a local repository, `mavenLocal()`, composite build, source
dependency, or moving version to a consumer.
