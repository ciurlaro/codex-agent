# Releasing

Version `0.2.0` has not yet been tagged or published. The release process is
designed to validate before merge, promote the exact validated bytes, and never
rebuild them during candidate assembly or publication.

No API key or stored ChatGPT credential is used by automated verification. A
real-model check uses interactive ChatGPT sign-in in the iOS Simulator test app.

## Merge gate and promotion

An unlabeled pull request is cheap: it runs workflow lint and impact planning,
but no product build or platform test. A non-draft pull request labeled
`merge-ready` validates its prospective merge tree and runs only the affected
lanes. Successful same-PR lanes and artifacts may be reused after a later
commit. The `ci:full` label only expands execution; it cannot narrow or skip a
required lane. Unknown impact fails closed to full validation.

The required `CI / merge-gate` check accepts an exact, complete receipt set. A
merge group whose Git tree is identical to the validated PR tree reuses that
validation without product work. If the target branch changes the tree, impact
is reevaluated and only the newly affected work runs.

After merge, the `Promote` workflow binds the landed commit's Git tree to the
equal validated merge-group tree and forwards the exact stored lane artifacts,
validation receipts, inventories, and selected cache seeds into `main` scope.
It never invokes Gradle, Cargo, Xcode, a compiler, a simulator, or a product
test, and it never falls back to rebuilding.

## Required GitHub configuration

The public organization is `codex-agent-labs`, `ciurlaro` is an owner, and the
canonical repository is `codex-agent-labs/codex-agent`. Configure them as
follows:

- Enable GitHub Actions, allow the already pinned third-party actions, keep the
  default workflow token read-only, give fork PRs read-only access, and expose
  no organization secret to PR or merge-group code.
- Retain CI and promoted artifacts for at least 90 days. Keep automatic branch
  deletion enabled and routine direct pushes or bypasses to `main` disabled.
- Protect `main` with required pull requests, merge queue, the merge-commit
  method rather than squash/rebase, a maximum merge-group size of one PR, and
  required check `CI / merge-gate`. Allow enough status-check time for full
  Apple validation, and protect `candidate/v*-rc.*` tags.
- Protect `merge-validation`, `release-candidate`, and `release-publication`.
  Keep Firebase OIDC configuration in `merge-validation`; keep signing and
  Maven Central credentials only in the candidate/publication environments,
  with required reviewers.
- Set `CI_MERGE_QUEUE_ENABLED=true` only after the trusted Android bootstrap
  described below is on `main` and the queue rules are ready.

## Candidate identity

A candidate tag must match `candidate/v<version>-rc.N`. It must identify an
exact commit on `main` whose Git tree has a complete promoted validation. The
workflow derives the release version from the tag instead of accepting an
unrelated version input, and fails if the promoted and candidate trees differ.

The protected `release-candidate` environment contains only the signing
material needed to assemble the payload. Its configured reviewers approve
access to those credentials. `release-publication` separately controls Maven
Central publication credentials and approval. `GRADLE_ENCRYPTION_KEY` is a CI
secret used only to encrypt reusable Gradle configuration-cache entries; it is
not publication authority.

## Evidence is produced once

1. Merge-ready validation builds each affected portable, Android, desktop,
   Node, and Apple slice and records its Git inputs, toolchain identity,
   artifacts, tests, and evidence in a lane receipt.
2. Desktop runtime evidence covers macOS Arm64/x64, Linux Arm64/x64, and
   Windows x64. Apple host, device, simulator, framework, Swift, privacy, and
   package stages remain independently reusable.
3. Firebase Test Lab evidence runs before merge against the exact Android APKs
   and AAR through the trusted workflow pinned to `main`; a candidate never
   repeats it and no connected physical phone is required.
4. Main promotion forwards equal-tree receipts and the exact bytes uploaded by
   the producing jobs. Candidate assembly verifies those promoted inputs,
   signs the unsigned Maven primaries, generates mandated sidecars, and
   assembles the Central bundle and release manifest without compiling,
   linking, or running platform tests.

Git commit, tree, blob, and explicit toolchain identity decide reuse. Checksums
remain for SwiftPM, Maven Central, signatures, pinned external inputs, GitHub
transport, and security-sensitive archive integrity. They never decide whether
source changed, key a lane, or compare independently rebuilt ZIP files.

## Protected candidate

Run the Release Candidate workflow from the candidate tag. Candidate assembly
uses a clean checkout and produces one immutable commit-scoped payload under:

```text
build/protected-candidate/<candidate-commit>/payload/
```

The aggregate verifies the imported evidence, iOS runtime, Swift package,
privacy declarations, Maven inventories, pre-merge consumer receipts, Central
bundle, and canonical candidate manifest. Candidate tasks may inspect,
inventory, sign, and assemble promoted files; they may not compile, link, run
Xcode, boot a simulator, or execute a platform test.

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

## Trusted Android rollout

The Firebase job has OIDC authority and deliberately executes workflow code
pinned to `main`, so the modernization must be enabled in three ordered steps:

1. Freeze candidate tags, then land the new trusted
   `android-runtime-evidence.yml` interface and its pinned-main dependencies on
   `main`: `ci/impact.py`, `ci/receipt.py`, `ci/evidence.py`, the Firebase
   evidence model/task/plugin files, and `setup-kmp`. The old candidate caller
   is intentionally unavailable during this short bootstrap window.
2. In a later change, land the CI caller and the remaining modernization that
   passes those exact bindings. Do not weaken the pin to `main` and do not give
   OIDC or release secrets to PR-controlled workflow code.
3. Confirm the trusted pre-merge evidence path, then set
   `CI_MERGE_QUEUE_ENABLED=true` and enable the one-PR merge queue ruleset.

Attempting the trusted workflow interface change and its new caller in one PR
fails closed because GitHub resolves reusable-workflow inputs from `main`.
Do not create a candidate tag until step 2 has landed.

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
     io.github.codex_agent_labs.CodexAgentTestApp data)
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
bytes and never rebuilds Maven, native, or runtime artifacts. It:

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
