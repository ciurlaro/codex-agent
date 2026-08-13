# Releasing

No API key or stored ChatGPT credential is part of this release process.
Automated verification is credential-free. A real model is accepted manually
through interactive ChatGPT browser login in the Swift test app.

## Protected candidate

The release candidate uses one immutable commit containing the final root
Package.swift checksum and every implementation, build, test, workflow, policy,
and documentation change.

1. Run the Desktop Runtime Evidence workflow against that commit. Its five
   GitHub-hosted runners execute the real initialize/shutdown smoke on macOS
   Arm64/x64, Linux Arm64/x64, and Windows x64, and bind each report to the
   exact classifier ZIP.
2. The successful exact-commit CI run builds the device and simulator Rust
   slices once in parallel after its fast gates. Each successful slice is
   retained independently for retry. The release-candidate workflow resolves
   that CI run, downloads its exact two artifacts, and rejects commit, status,
   workflow, or evidence mismatches.
3. From a clean checkout of the same commit, the Apple aggregate imports and
   hash-verifies the exact five root files without rebuilding either slice, then
   assembles the technical candidate once:

       DEVELOPER_DIR=/Applications/Xcode_26.6.app/Contents/Developer \
         ./gradlew assembleProtectedCandidate \
         -PcodexAgent.candidateCommit=<40-character-candidate-commit> \
         -PcodexAgent.releaseTag=v0.2.0 \
         -PcodexAgent.desktopEvidenceDirectory=<desktop-evidence-directory> \
         -PcodexAgent.iosNativeEvidenceDirectory=<merged-native-evidence-directory> \
         --no-parallel

The release-candidate environment supplies only Maven signing material. The
task requires a clean checkout at the supplied commit, isolated external
five-target desktop evidence, and the exact imported device/simulator native
evidence. It runs the ordered native-import verification, iOS,
Swift, privacy, Maven, clean-consumer, Central bundle, and candidate-manifest
gates once without a Gradle clean. The canonical `swiftpm-proof.json` binds the
commit and tree, clean checkout, exact ZIP and checksum file, committed
Package.swift metadata, native provenance, and pinned Apple toolchain. The
manifest and payload bind that proof, the desktop runtime evidence, the exact SwiftPM
ZIP, and the Central bundle under:

    build/protected-candidate/<candidate-commit>/payload/

Candidate output is immutable. Assembly refuses to delete or rebuild an
existing commit-scoped candidate directory; remove an incomplete directory only
after diagnosing the failed run, then start one fresh assembly.

The clean KMP consumer resolves this project only from CENTRAL_STAGING and
compiles JVM, Android, JS, WasmJS, macOS Arm64/x64, Linux Arm64/x64, and Windows
x64, plus links iOS Arm64 and iOS Simulator Arm64. The typed Central bundle
task validates all 22 coordinates and 133 primary artifacts, signatures, Maven
metadata, licence declarations, deterministic ZIP inventory, and the strict
1,000,000,000-byte Portal limit before the canonical candidate manifest is
generated and fully reverified.

The standalone Android Runtime Evidence workflow remains available as an
optional real-device diagnostic. It is not a protected-candidate or publication
requirement. Android compilation, tests, lint, AAR publication, and clean KMP
consumer verification remain mandatory and require no connected phone.

Run the repository gates separately when developing the candidate:

    actionlint
    ./gradlew -p buildSrc test
    ./gradlew verifyReleaseMetadata -PcodexAgent.releaseTag=v0.2.0
    ./gradlew verifyRepository
    DEVELOPER_DIR=/Applications/Xcode_26.6.app/Contents/Developer \
      ./gradlew verifyIosRuntime

After the technical payload is uploaded, run:

    ./gradlew verifyPublicationReadiness

This readiness gate is intentionally separate from candidate assembly. Until
the external Apple collected-data and static-framework GPL decisions are
approved, the technical payload remains available but publication stays
blocked. The decisions live in release/publication-approvals.json and are bound
to the exact privacy manifest and release/privacy-data-flow-review.json.
Desktop classifier GPL distribution is reviewed separately and bound to its
exact distribution inventory, licence, and notice; it does not approve the
static Apple framework.
Required-reason API reviews are supplied separately only when the static audit
finds an ambiguous API requiring a manual disposition.

## Manual ChatGPT browser-login acceptance

This required real-model test deliberately uses no reusable CI credential.

1. Stage the test application:

       DEVELOPER_DIR=/Applications/Xcode_26.6.app/Contents/Developer \
         ./gradlew :codex-agent-runtime-ios:stageCodexAgentAppleDistribution

2. Open
   codex-agent-runtime-ios/build/apple-distribution/CodexAgentTestApp/CodexAgentTestApp.xcodeproj
   in Xcode. Select an iOS Simulator and run CodexAgentTestApp.
3. Tap Sign in with ChatGPT and complete sign-in inside the secure system
   browser sheet. The embedded App Server remains responsible for PKCE,
   callback handling, token persistence, refresh, and completion events.
4. Wait for Authenticated, then tap Run local workspace acceptance and require
   PASS.
5. On Simulator, compare the sandbox files independently:

       APP_DATA=$(xcrun simctl get_app_container booted \
         io.github.ciurlaro.CodexAgentTestApp data)
       cmp "$APP_DATA/Documents/CodexWorkspace/acceptance-input.txt" \
         "$APP_DATA/Documents/CodexWorkspace/acceptance-output.txt"

6. Repeat on a signed physical iPhone before release. Record physical execution
   as unproven when no signing team or device is available.

## Protected publication

Publication consumes the exact successful candidate payload and never rebuilds
Maven or native artifacts.

1. The Publish verified release workflow downloads the exact candidate artifact
   from its successful release-candidate workflow run.
2. verifyCandidatePayload recomputes every artifact, evidence, policy, commit,
   tag, and Package.swift binding before any public mutation.
3. verifyPublicationReadiness runs again after protected-environment approval.
4. The workflow creates or reuses the exact draft GitHub release.
5. prepareCentralDeployment uploads the verified bundle as USER_MANAGED only
   when creating a new matching deployment, then immediately records its
   deployment ID, name, candidate hash, and bundle hash.
6. The workflow persists that record on the draft before
   awaitCentralValidation waits for VALIDATED.
7. It attaches and byte-checks the exact SwiftPM ZIP and candidate manifest,
   publishes the GitHub release, and verifies the tag identity and public
   SwiftPM resolution.
8. releaseCentralDeployment releases that same validated deployment and waits
   for PUBLISHED. A rerun reuses an exact matching validated or published
   record; missing records and identity/hash mismatches fail closed.

Only Maven Central credentials and signing material belong in the protected
release environments. Do not store OPENAI_API_KEY, ChatGPT credentials, or
generated tokens.

Before release, manually cover interactive ChatGPT login,
background/foreground transitions, forced termination/relaunch, a signed
physical iPhone, public SwiftPM resolution, Maven Central resolution, and final
codex-mobile verification. Do not update a consumer repository from this
project.
