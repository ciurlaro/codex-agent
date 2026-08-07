# Releasing

No API key or stored ChatGPT credential is part of this release process.
Automated verification is credential-free; the real-model acceptance test uses
interactive ChatGPT browser login in the Swift test app.

## Automated candidate verification

1. On Apple Silicon macOS with full Xcode, Rust `1.95.0`, and both Apple Rust
   targets installed, run:

   ```shell
   ./gradlew -p buildSrc test
   ./gradlew verifyReleaseMetadata -PcodexAgent.releaseTag=v0.2.0
   ./gradlew verifyRepository
   DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
     ./gradlew verifyIosRuntime
   ```

   This credential-free gate proves native tests, device/simulator compilation
   and linking, embedded-runtime startup, the shared JSON-RPC handshake,
   shutdown/restart, workspace confinement, the advertised five-tool profile,
   tool dispatch, creation and cancellation of the real Codex-managed browser
   login route and localhost callback, XCFramework packaging, and clean local
   Swift consumption. It does not present the browser sheet or prove a model
   request.

2. Run the Android runtime instrumentation smoke test on an ARM64 Android host.

3. Build the immutable SwiftPM binary artifact and checksum:

   ```shell
   ./gradlew \
     :codex-agent-runtime-ios:packageCodexAgentSwiftPackageBinary \
     :codex-agent-runtime-ios:generateCodexAgentSwiftPackageChecksum
   swift package compute-checksum \
     codex-agent-runtime-ios/build/distributions/CodexAgent-0.2.0.xcframework.zip
   ```

   The Swift result must equal
   `codex-agent-runtime-ios/build/distributions/CodexAgent-0.2.0.xcframework.zip.sha256`
   and the checksum in the root `Package.swift`. Then run
   `:codex-agent-runtime-ios:verifyCodexAgentRemoteSwiftPackage`. Rebuilding the
   ZIP from unchanged inputs must produce the same checksum.

## Manual ChatGPT browser-login acceptance

This is the required real-model release test; it is deliberately not automated
with a reusable credential.

1. Stage the test application:

   ```shell
   DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
     ./gradlew :codex-agent-runtime-ios:stageCodexAgentAppleDistribution
   ```

2. Open
   `codex-agent-runtime-ios/build/apple-distribution/CodexAgentTestApp/CodexAgentTestApp.xcodeproj`
   in Xcode. Select an iOS Simulator and run `CodexAgentTestApp`.
3. Tap **Sign in with ChatGPT**. Complete sign-in inside the secure system
   browser sheet. The sheet must dismiss automatically and return to the app;
   there must be no PIN to copy and no second app-owned token exchange. Do not
   copy credentials or tokens into the project, CI, or GitHub secrets.
4. Wait for **Authenticated**, then tap **Run local workspace acceptance**.
5. Require the app to display `PASS`. The test gives the model only the input
   filename, asks it to read that local file, and requires it to patch the output
   file to identical bytes. Any timeout or unexpected bytes fails acceptance.
6. For Simulator, independently compare the sandbox files:

   ```shell
   APP_DATA=$(xcrun simctl get_app_container booted \
     io.github.ciurlaro.CodexAgentTestApp data)
   cmp "$APP_DATA/Documents/CodexWorkspace/acceptance-input.txt" \
     "$APP_DATA/Documents/CodexWorkspace/acceptance-output.txt"
   ```

7. Repeat on a signed physical iPhone before release. If no device or signing
   team is available, record physical-device execution as unproven; device
   compilation/linking and the Simulator browser-login acceptance must still
   pass.

## Publishing after acceptance

1. Set one immutable version in the root build; never reuse a released version. The
   publication workflow passes the actual GitHub release tag to
   `verifyReleaseMetadata`, which must match the Gradle version, SwiftPM URL and
   filename, and RemoteConsumer exact dependency before publication.
2. Commit the matching root `Package.swift` URL/checksum and all candidate
   sources. Rebuild the binary ZIP from that exact commit and confirm its
   checksum is unchanged.
3. Create a draft GitHub release targeting that commit and upload exactly
   `CodexAgent-0.2.0.xcframework.zip`. Do not publish the draft until all prior
   checks and manual acceptance pass.
4. Configure only the Maven Central secrets
   `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`,
   `SIGNING_IN_MEMORY_KEY`, and `SIGNING_IN_MEMORY_KEY_PASSWORD`.
5. Publish the matching release/tag. The release workflow rebuilds the
   credential-free gates, resolves the public URL from a clean remote SwiftPM
   consumer, and only then publishes to Maven Central.
6. Resolve all three Maven coordinates and the public Swift package from clean
   consumers before updating any consumer repository.

For migration-only Maven verification, publish the same fixed version to an
isolated local repository:

```shell
./gradlew publishAllPublicationsToMigrationRepository \
  -PcodexAgent.localRepository=/tmp/codex-agent-maven
```

Do not commit a local repository, `mavenLocal()`, composite build, source
dependency, or moving version to a consumer.
