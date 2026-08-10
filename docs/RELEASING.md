# Releasing

No API key or stored ChatGPT credential is part of this release process.
Automated verification is credential-free; the real-model acceptance test uses
interactive ChatGPT browser login in the Swift test app.

## Phase 1: automated candidate verification

Run the **Release candidate** workflow manually from a protected ref with an
exact `vX.Y.Z` input, or push a protected `candidate/vX.Y.Z` tag. The workflow
rejects an unprotected source ref. Configure the `release-candidate` environment
for the signing key required to construct the exact Central bundle. The
workflow has read-only repository permission and never creates a tag or GitHub
release and never uploads to Maven Central.

1. On Apple Silicon macOS with full Xcode, Rust `1.95.0`, and both Apple Rust
   targets installed, run:

   ```shell
   ./gradlew -p buildSrc test
   ./gradlew verifyReleaseMetadata -PcodexAgent.releaseTag=v0.2.0
   ./gradlew verifyRepository
   DEVELOPER_DIR=/Applications/Xcode_26.6.app/Contents/Developer \
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
   Use the manual `Android Runtime Evidence` workflow. Its artifact records the
   exact commit, command, device ABI/API, result, test/target APK hashes, and
   embedded runtime hash. `verifyProtectedCandidate` rejects missing, stale, or
   non-passing evidence.

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

   CI preserves the ZIP and checksum before running the committed-checksum
   gate. If that gate fails after an intentional binary change, use the failed
   candidate artifact only to review and commit the new root `Package.swift`
   checksum, then rerun the complete candidate; a failed candidate is never a
   publication input.

The candidate workflow performs those gates in measured, sequential phases. It
records actual filesystem peak/minimum-free space, system memory availability,
and command-process-tree resident memory for Rust, XCFramework assembly,
Swift packaging, and Maven staging. APFS clone-on-write staging avoids physical
copies of the multi-gigabyte static framework; safe Apple packaging
intermediates are removed before Maven staging, and staged Maven files are
consumed as they are streamed into the deterministic Portal ZIP.

It creates, without publishing, the exact SwiftPM ZIP and the exact signed
Central Portal bundle. `release-artifact-report.json` contains every Swift ZIP
member, every static archive member grouped by dependency, every generated
Maven artifact, every Central ZIP entry, hashes, sizes, and the phase resource
reports. `build_central_bundle.py` enforces a conservative
`1,000,000,000`-byte Portal upload ceiling and fails on unsigned artifacts. A
candidate over that limit is not publication-ready: do not drop the iOS
artifact implicitly. Either reduce it without changing runtime behavior, or
make SwiftPM the explicit reviewed iOS distribution channel before retrying.

The exact payload is retained as a GitHub Actions artifact. The final candidate
step runs `verifyPublicationReadiness`; while privacy or static-framework GPL
approval is false, the payload remains available as evidence but the workflow
conclusion is failure, so publication cannot start.

After all source changes, run:

```shell
./gradlew :codex-agent-runtime-ios:verifyCodexAgentSwiftPackageReproducibility
```

It performs two clean builds and requires byte-for-byte ZIP equality.

4. Stage and consume the exact Maven repository before bundling it:

   ```shell
   ./gradlew verifyStagedKmpConsumer generateCentralBundleInventory
   ```

   The isolated consumer resolves `io.github.ciurlaro` only from
   `CENTRAL_STAGING` and compiles JVM, Android, iOS Arm64, and iOS Simulator
   Arm64. The deterministic Central bundle is created only after this passes.

## Manual ChatGPT browser-login acceptance

This is the required real-model release test; it is deliberately not automated
with a reusable credential.

1. Stage the test application:

   ```shell
   DEVELOPER_DIR=/Applications/Xcode_26.6.app/Contents/Developer \
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

## Phase 2: protected publication

1. Set one immutable version and commit the matching root `Package.swift`
   checksum before starting the candidate. Never reuse a released version.
2. Require reviewers on the `release-publication` GitHub environment. Store
   only `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` there; candidate
   signing happens in phase 1. No ChatGPT credential or `OPENAI_API_KEY` is
   allowed in either environment.
3. Run the manual publication workflow with the exact candidate commit and
   Android evidence run. A successful **Release candidate** `workflow_run` is
   the only publication trigger; it passes the actual release tag, exact
   candidate commit, Android evidence, Swift ZIP, and Central bundle forward
   without rebuilding them.
4. The protected publication job creates or reuses a draft GitHub release,
   uploads the exact prebuilt Central bundle as a `USER_MANAGED` deployment, waits for
   `VALIDATED`, records the deployment ID and hashes on the draft, then
   publishes the GitHub release and verifies public SwiftPM resolution before
   releasing that same Central deployment. A rerun reuses the matching record
   and treats an already `PUBLISHED` deployment as success.
5. Configure only the Maven Central secrets
   `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`,
   `SIGNING_IN_MEMORY_KEY`, and `SIGNING_IN_MEMORY_KEY_PASSWORD`.
6. Never invoke Maven publication tasks after candidate assembly. Central
   release consumes only the recorded bundle and rejects deployment-name,
   candidate-hash, or bundle-hash mismatches.
7. Resolve all three Maven coordinates and the public Swift package from clean
   consumers before updating any consumer repository.

Publication also requires `verifyPublicationReadiness`. It remains blocked
until the exact Apple collected-data declarations and static-framework GPL
distribution decision are approved in `release/0.2.0-approvals.json`. Inspect
the archived sample in Xcode Organizer and retain its aggregate privacy report.
The false privacy approval permits a pending data-flow inventory but does not
permit publication. Approval requires an approved terminal declaration or a
reviewed no-declaration rationale, bound to exact manifest/inventory hashes.

Before release, manually cover interactive ChatGPT login, background/foreground
transitions, forced termination/relaunch, a signed physical iPhone, public
SwiftPM resolution, Maven Central resolution, and final `codex-mobile`
verification. Credential-free CI does not claim any of those results.

For migration-only Maven verification, publish the same fixed version to an
isolated local repository:

```shell
./gradlew publishAllPublicationsToMigrationRepository \
  -PcodexAgent.localRepository=/tmp/codex-agent-maven
```

Do not commit a local repository, `mavenLocal()`, composite build, source
dependency, or moving version to a consumer.
