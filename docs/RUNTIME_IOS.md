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

The checked-in upstream adapter exposes `start_uninitialized`. The common
`AppServerConnection` remains the sole owner of `initialize` / `initialized`.
Native and simulator tests start through that connection twice, while Rust
tests also verify that a second initialization is rejected.
The native host does not invent a client version: the actual version travels in
the shared client's `initialize` request.

## Local capability profile

The selected workspace must already be an absolute directory below the
application sandbox root. Conversation state, credentials, and App Server state
are stored below the configured sandbox-local Codex home. Those are the only
runtime path settings. The former unused `temporaryPath` property remains as a
deprecated, inert source-compatibility property and no longer creates an unused
directory. Equivalent workspace spellings are compared through normalized real
paths.
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

## Authentication and Apple consumption

The shared client uses the existing App Server authentication routes. The
supported iOS end-user path is Codex-managed ChatGPT browser login presented by
the `CodexAgentAuthentication` SwiftPM product through
`CodexChatGPTAuthenticationSession`. It uses `ASWebAuthenticationSession` with
the user's normal Safari session. The embedded App Server still generates PKCE,
hosts the temporary `127.0.0.1:1455` (or registered fallback-port) callback,
exchanges the code, persists and refreshes the ChatGPT credential, and emits
`account/login/completed`. The Swift wrapper sees only the authorization URL and
the existing `AgentEvent` stream; it dismisses the browser sheet when
`AgentEvent.Authenticated` arrives. No separate app deep link, duplicate token
exchange, or second handshake is introduced.

The facade owns the single common-client event collector and rebroadcasts each
event through independent bounded observer mailboxes. The authentication
wrapper and application observers can therefore subscribe simultaneously.
Minimal usage requires both imports:

```swift
import CodexAgent
import CodexAgentAuthentication

let authentication = CodexChatGPTAuthenticationSession(facade: facade)
authentication.eventHandler = { event in
    // Handle the same AgentEvent values used by the Kotlin client.
}
authentication.authenticate { error in
    // nil only after AgentEventAuthenticated; otherwise show the error.
}
```

ChatGPT browser OAuth is the only authentication flow exposed by the iOS
facade and Swift application. API-key login remains an optional shared-client
capability, but no API key or persisted interactive credential is required by
CI or release automation. The iOS configuration forces the upstream file
credential store into the local Codex home. Secrets are not included in runtime
configuration or diagnostic strings.

`assembleCodexAgentReleaseXCFramework` creates the static umbrella framework.
`packageCodexAgentAppleDistribution` stages its local Swift Package and creates
`build/distributions/CodexAgentPackage-0.2.0.zip`. The package exports the
shared client plus iOS runtime as `CodexAgent` and the small native browser
presenter as `CodexAgentAuthentication`. The facade only adds lifecycle and
authentication operations. `apple/TestApp` is a standalone SwiftUI consumer
project. All Rust binaries, package metadata, and the test app target iOS 15 or
newer.
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
rustup toolchain install 1.95.0 --profile minimal \
  --target aarch64-apple-ios,aarch64-apple-ios-sim
DEVELOPER_DIR=/Applications/Xcode_26.6.app/Contents/Developer \
  ./gradlew verifyIosRuntime
```

This credential-free gate verifies the native bridge, iPhoneOS and Simulator
compilation/linking, real embedded App Server startup and shared JSON-RPC
handshake, deterministic restart, workspace confinement, exact tool
advertisement/dispatch, XCFramework creation, Swift Package staging, checksum
metadata, and a clean Swift app build. It does not authenticate or claim a real
model call.

Follow the [manual release acceptance procedure](RELEASING.md) to use the
ChatGPT browser sheet and prove a real model reads and patches a local sandbox
file. Signed physical-device execution remains an external release gate when no
device/team is available; physical compilation/linking and Simulator acceptance
remain required.
