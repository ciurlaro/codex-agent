# Local iOS runtime

`codex-agent-runtime-ios` runs Codex App Server locally and in-process. It does
not launch or download an executable, use a remote workspace, or connect to a
gateway/WebSocket proxy. Normal HTTPS access from Codex to the OpenAI model API
is allowed.

## Architecture

The module builds OpenAI Codex `0.145.0` from revision
`25af12f7e61572b0bc18ddb1008be543b91519b0` with Rust `1.95.0`. The source
archive is verified against SHA-256
`42f627a7b32db41582c73a8eafd9ec4b35d6c3ff81bd3d4455cfd6224d79d329`
before extraction. Full provenance is in `native/provenance.json`.
That record also fixes the archive byte count and SHA-256 values for the
upstream `Cargo.lock`, adapter patch, bridge manifest/source, and public C
header. Every Cargo build/test uses `--locked`; source preparation rejects a
lockfile that does not match the recorded SHA-256.

The narrow C ABI owns one opaque runtime and bounded 64-message command/event
queues. Kotlin sends and receives the same UTF-8 JSON-RPC lines used by
`CodexRuntime`; no protocol model layer is duplicated. Buffers have explicit
free semantics, shutdown joins the native worker before destruction, and the
Kotlin receiver is joined before its handle is released, so callbacks cannot
arrive after close.

The checked-in upstream adapter exposes `start_uninitialized`. The internal
connection owned by the prepared `CodexAgent` remains the sole owner of
`initialize` / `initialized`. Native and simulator tests start through that
connection twice, while Rust tests also verify that a second initialization is
rejected. The native host does not invent client identity: the embedding
application supplies `CodexClientInfo`, and its name, title, and version travel
in the initialize request.

## Local capability profile

The selected workspace may be an absolute directory below the application
sandbox or a folder URL granted by the user through the host application's
document picker. `IosCodexPlatform` stores an atomic security-scoped
bookmark, resolves it on later launches, holds access for the runtime lifetime,
and reports `ACCESS_REVOKED` when a bookmark is stale or unavailable. The host
owns picker presentation; construct `IosCodexWorkspaceSelection` from the URL it
returns. Workspace tool reads and writes use `NSFileCoordinator` while the grant
is active.

Conversation state, credentials, and App Server state remain below the
configured sandbox-local Codex home even when the workspace is external. The
unused `temporaryPath` configuration has been removed. Equivalent workspace
spellings are compared through normalized real paths.
Only one active local runtime may own a canonical Codex home in a process. A
second runtime is rejected until the first has shut down or failed cleanly.

The model receives these dynamic tools:

- `apply_patch` for bounded, workspace-confined Codex patches;
- `read_file` for bounded UTF-8 reads;
- `list_directory` for one bounded directory listing;
- `search_text` for bounded recursive literal search;
- `write_file` for synchronized atomic replacement.

The `apply_patch` tool reuses Codex's pinned Rust parser and applicator behind
the same workspace boundary. Paths are canonicalized; `..`, absolute paths,
and symlink components are rejected; atomic replacement prevents writes through
hard links. App Server thread/turn requests are confined to the selected
workspace with workspace-write policy.

`search_text` stops at 10,000 visited files, 1,000 visited directories, depth
32, 64 MiB scanned, 200 matches, or 256 KiB of output. Reaching any budget adds
an explicit truncation line naming the exhausted budget and the observed file,
directory, and byte counts.

Shell/process execution, native Git, build tools, process hooks, apps/plugins,
MCP servers, external-agent import, and unscoped filesystem routes are disabled.
They are omitted from the advertised tool set; direct calls to represented
unsupported routes return JSON-RPC error `-32004`. The injected execution
environment list is empty, so process-backed tools cannot be planned.
Accordingly the prepared runtime advertises exactly
`setOf(CodexRuntimeFeature.SKILLS)`; the public agent rejects every unsupported
feature operation before it reaches the native bridge.

## Authentication and Apple consumption

`CodexAgent` uses the existing App Server authentication routes. The
`CodexAgentAuthentication` SwiftPM product provides only
`CodexWebAuthenticationBrowser`, which implements the host's browser contract
with `ASWebAuthenticationSession` for ChatGPT, connector, MCP OAuth, and
elicitation URLs. It does not provide a second client, authentication session,
host coordinator, event broadcaster, or reducer.

`CodexAgentObservation` provides typed, cancellation-safe `AsyncStream` views
of the same public Kotlin state: `CodexHost.states`, the four `CodexAgent`
streams, and `CodexConversation.states`. Terminating a Swift task closes its
Kotlin collection token, so an abandoned stream does not retain a collector.
There is no parallel Swift state model.

`CodexAgentSwiftSupport` adds only the default operations that Kotlin default
arguments cannot express naturally in Swift: `agent.authenticate()`,
`agent.openConversation()`, and `conversation.send(_:)`. It also exposes
`Error.codexFailure` for the stable failure carried by a thrown
`CodexOperationException`. Advanced generated overloads remain available.

```swift
import CodexAgent
import CodexAgentObservation
import CodexAgentSwiftSupport

let host = CodexHost(
    platform: platform,
    clientInfo: CodexClientInfo(
        name: "com.example.app",
        title: "Example App",
        version: appVersion
    )
)
try await host.start()

for await state in host.states {
    guard let ready = state as? CodexHostStateReady else { continue }
    let conversation = try await ready.agent.openConversation()
    try await conversation.send("Hello")
    break
}
```

The embedded App Server generates PKCE, hosts the temporary loopback callback,
exchanges the code, persists and refreshes the credential, and emits completion
to the host. The browser adapter sees only a validated authorization URL. There
is no app deep link, duplicate token exchange, or wrapper-owned token. ChatGPT
URLs use strict HTTPS OpenAI/ChatGPT host validation; external authorization
accepts HTTPS or loopback HTTP. The iOS runtime keeps the upstream file
credential store inside the configured Codex home.

`assembleCodexAgentReleaseXCFramework` creates the static umbrella framework.
`packageCodexAgentAppleDistribution` stages its local Swift Package and creates
`build/distributions/CodexAgentPackage-0.2.0.zip`. The package exports the
shared host plus iOS runtime as `CodexAgent`, the native browser adapter as
`CodexAgentAuthentication`, and the state overlay as
`CodexAgentObservation`; `CodexAgentSwiftSupport` supplies the small Swift call
overlay. `apple/TestApp` is a standalone SwiftUI consumer that constructs
`CodexHost`, observes its ready `CodexAgent`, opens a `CodexConversation`, and
closes the host. All Rust binaries, package metadata, and the test app target
iOS 15 or newer.
The `0.2.0` binary supports iPhoneOS Arm64 and Apple Silicon Simulator Arm64.
Intel Simulator (`iosX64`) is intentionally unsupported.

`packageCodexAgentSwiftPackageBinary` creates the reproducible release asset
`CodexAgent-0.2.0.xcframework.zip`; its generated checksum must match the root
URL-based `Package.swift`. `apple/RemoteConsumer` is a clean consumer of the
public repository. It can resolve only after the matching immutable release
asset exists, so it runs after release publication and is not claimed by local
pre-release verification.

## Verification

Run on an Apple Silicon macOS host with full Xcode selected through
`DEVELOPER_DIR` and the pinned Rust toolchain/targets installed:

```shell
export DEVELOPER_DIR=/Applications/Xcode_26.6.app/Contents/Developer
rustup toolchain install 1.95.0 --profile minimal \
  --target aarch64-apple-ios,aarch64-apple-ios-sim
./gradlew :codex-agent-runtime-ios:clean
./gradlew :codex-agent-runtime-ios:preflightIosRuntime
./gradlew :codex-agent-runtime-ios:verifyCodexAgentSwiftSimulatorCompilation
# Freeze the source tree here; do not edit it during either remaining gate.
./gradlew verifyIosRuntime
./gradlew verifyRepository
```

The preflight requires 40 GiB free by default. Override that only for a
deliberately smaller check with
`-PcodexAgent.iosMinimumFreeDiskGiB=<positive-integer>`.
Run focused unit tests before the simulator step when changing shared code.
The scoped clean happens once at the start; do not delete Cargo or Gradle
intermediates while a build is running. The Swift task stages a temporary
simulator-only package and runs
`build-for-testing`. It builds no device slice and boots no Simulator. Its typed
Gradle task declares package, framework, and pinned toolchain inputs, keeps
DerivedData as local state, and caches its success report. Budget 45–90 minutes
for a cold full gate and keep the Gradle caches between attempts.

For an exact clean commit already covered by exported Apple native evidence,
the full gate can reuse the five verified slice/proof files instead of
rebuilding them:

```shell
./gradlew verifyIosRuntime \
  -PcodexAgent.candidateCommit=<exact-commit> \
  -PcodexAgent.iosNativeEvidenceDirectory=<absolute-evidence-directory>
```

Evidence reuse intentionally fails if the commit, tree, native inputs,
compiler, Rust toolchain, Xcode, or Swift identity differs.

This credential-free gate verifies the native bridge, iPhoneOS and Simulator
compilation/linking, real embedded App Server startup and shared JSON-RPC
handshake, deterministic restart, bookmark restoration and workspace
confinement, exact tool advertisement/dispatch, XCFramework creation, Swift
Package staging, checksum metadata, and a clean Swift app build. It does not
authenticate or claim a real model call.

Follow the [manual release acceptance procedure](RELEASING.md) to use the
ChatGPT browser sheet and prove a real model reads and patches a local sandbox
file. Signed physical-device execution remains an external release gate when no
device/team is available; physical compilation/linking and Simulator acceptance
remain required.
