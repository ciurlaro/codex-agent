# Releasing

Version `0.2.0` has not yet been tagged or published. The release process is
designed to promote one verified commit and its existing evidence without
performing the same expensive work twice.

No API key or stored ChatGPT credential is used by automated verification. A
real-model check uses interactive ChatGPT sign-in in the iOS Simulator test app.

## Candidate identity

A candidate tag must match `candidate/v<version>-rc.N`. It must identify an
exact commit on `main` with a successful same-repository CI run for that commit.
The workflow derives the release version from the tag instead of accepting an
unrelated version input.

The protected candidate environment contains only the signing material needed
to assemble the release payload. Its configured reviewers approve access to
those credentials. The protected release environment separately controls Maven
Central publication credentials and approval. `GRADLE_ENCRYPTION_KEY` is a
repository CI secret used only to encrypt reusable Gradle configuration-cache
entries; it is not publication authority.

## Evidence is produced once

1. The successful exact-commit `main` CI run completes the repository gates,
   packages the three portable evidence runners once, and builds the Android
   application APK, test APK, and release AAR once.
2. That same CI run calls the Desktop Runtime Evidence workflow. Its five-host
   matrix covers macOS Arm64/x64, Linux Arm64/x64, and Windows x64. Each host
   runs native desktop, JVM desktop, JS-on-Node, and WasmJS-on-Node lifecycle
   checks through the platform bundle installer against its exact matching
   classifier. Linux Arm64 transports one combined evidence bundle.
3. Apple host-native tests run independently on Linux while the device and
   simulator slices build in parallel on macOS. One downstream Apple job
   verifies those five evidence files and exports the complete verified Apple
   distribution once.
4. The protected candidate runs Firebase Test Lab `SmallPhone.arm` API 35
   against the imported exact-main Android APKs. It downloads only the exact
   test XML, records the tested binaries and AAR, and requires no connected
   physical phone.
5. Candidate assembly imports the attempt-bound portable runners, five
   classifiers, five-host evidence, Android evidence/AAR, Apple native evidence,
   and whole verified Apple distribution. It runs only aggregate packaging and
   consumer gates; none of those expensive platform artifacts is rebuilt.

Retries reuse a logically matching successful run or artifact when one exists.
Two independent builds are not required to have identical bytes or hashes.
Hashes, signatures, commit bindings, and platform-required checks still verify
each specific artifact that enters the candidate.

## Protected candidate

Run the Release Candidate workflow from the candidate tag. Candidate assembly
uses a clean checkout and produces one immutable commit-scoped payload under:

```text
build/protected-candidate/<candidate-commit>/payload/
```

The aggregate verifies the imported evidence, iOS runtime, Swift package,
privacy declarations, Maven publications, clean consumer, Central bundle, and
canonical candidate manifest once. The clean consumer compiles the published
surface for Android, iOS, five native desktop targets, JVM desktop, JS-on-Node,
and WasmJS-on-Node.

Candidate output is immutable. A rerun reuses an already successful candidate;
it never silently deletes or replaces one with the same identity.

Useful local gates while developing are:

```shell
actionlint
./gradlew -p gradle/build-logic test
./gradlew verifyReleaseMetadata -PcodexAgent.releaseTag=v0.2.0
export DEVELOPER_DIR=/Applications/Xcode_26.6.app/Contents/Developer
./gradlew :codex-agent-runtime-ios:preflightIosRuntime
./gradlew verifyIosRuntime
./gradlew verifyRepository
```

None of these commands requires a connected Android phone.
Follow the [iOS development verification order](RUNTIME_IOS.md#verification)
before starting the expensive Apple gate; it includes the scoped clean,
simulator-only Swift typecheck, source freeze, disk budget, and exact-evidence
reuse rules.

## Manual ChatGPT acceptance

The real-model test is deliberately interactive and keeps reusable credentials
out of CI.

1. Stage the test application:

   ```shell
   DEVELOPER_DIR=/Applications/Xcode_26.6.app/Contents/Developer \
     ./gradlew :codex-agent-runtime-ios:stageCodexAgentAppleDistribution
   ```

2. Open
   `codex-agent-runtime-ios/build/apple-distribution/CodexAgentTestApp/CodexAgentTestApp.xcodeproj`
   in Xcode and run it in an iOS Simulator.
3. Tap **Sign in with ChatGPT**, complete sign-in in the secure system browser
   sheet, and wait for **Authenticated**.
4. Tap **Run local workspace acceptance** and require **PASS**.
5. Independently compare the sandbox files:

   ```shell
   APP_DATA=$(xcrun simctl get_app_container booted \
     io.github.ciurlaro.CodexAgentTestApp data)
   cmp "$APP_DATA/Documents/CodexWorkspace/acceptance-input.txt" \
     "$APP_DATA/Documents/CodexWorkspace/acceptance-output.txt"
   ```

A signed physical-iPhone run may be performed as additional product testing,
but it is not a release gate.

## Publication approvals

`verifyPublicationReadiness` remains separate from technical candidate
assembly. Publication stays blocked until the repository's Apple collected-data,
static-framework GPL, and desktop-classifier distribution decisions approve the
exact candidate inputs. Required-reason API dispositions are needed only when
the static audit reports an ambiguity.

Google authentication for Firebase evidence uses GitHub OIDC and Workload
Identity Federation. It needs no stored Google service-account key. Creating or
authorizing the Google identity, generating Maven Central credentials, and
approving protected environments remain external account-owner actions.

## Protected publication

The Publish Verified Release workflow consumes the exact successful candidate
artifact and never rebuilds Maven, native, or runtime evidence artifacts. It:

1. Resolves the candidate workflow and release tag from the candidate identity.
2. Revalidates every artifact, evidence record, policy decision, commit, tag,
   Swift package binding, signature, and candidate-manifest entry before public
   mutation.
3. Waits for protected release-environment approval, then uses an Ubuntu job to
   create or reuse the matching Maven Central deployment and GitHub draft
   release.
4. Promotes only the recorded Central bundle and exact Swift package/candidate
   assets, comparing the official GitHub asset digest with the manifest-bound
   artifact without downloading it again.
5. Runs one downstream macOS job whose only public asset download is the clean
   Swift Package resolution check.
6. On rerun, reuses matching validated or published records and fails closed on
   identity mismatches. It does not compare a new rebuild with the old one.

Do not store `OPENAI_API_KEY`, ChatGPT credentials, generated tokens, or Google
service-account keys in the release environments. Final consumer application
acceptance and any optional broader device testing remain outside this
repository's automated release.
