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
adapter patch, bridge manifest/source, and public C header.

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

## Local capability profile

The selected workspace must already be an absolute directory below the
application sandbox root. Conversation state, credentials, and App Server state
are stored below the configured sandbox-local Codex home.

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

Shell/process execution, native Git, build tools, process hooks, apps/plugins,
MCP servers, external-agent import, and unscoped filesystem routes are disabled.
They are omitted from the advertised tool set; direct calls to represented
unsupported routes return JSON-RPC error `-32004`. The injected execution
environment list is empty, so process-backed tools cannot be planned.

## Authentication and Apple consumption

The shared client uses the existing App Server authentication routes. Browser
login remains the default; device-code and explicit API-key login are additive.
The iOS configuration forces the upstream file credential store into the local
Codex home. Secrets are not included in runtime configuration or diagnostic
strings.

`assembleCodexAgentReleaseXCFramework` creates the static umbrella framework.
`packageCodexAgentAppleDistribution` stages its local Swift Package and creates
`build/distributions/CodexAgentPackage-0.2.0.zip`. The package exports the
shared client plus iOS runtime; the facade only adds lifecycle/authentication
operations and event observation. `apple/TestApp` is a standalone SwiftUI
consumer project.

## Verification

Run on an Apple Silicon macOS host with full Xcode selected through
`DEVELOPER_DIR` and the pinned Rust toolchain/targets installed:

```shell
rustup toolchain install 1.95.0 --profile minimal \
  --target aarch64-apple-ios,aarch64-apple-ios-sim
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew verifyIosRuntime
```

This verifies the native bridge, iPhoneOS and Simulator compilation/linking,
simulator lifecycle/workspace behavior, XCFramework creation, Swift Package
staging, and a clean Swift app build. Set `OPENAI_API_KEY` to also execute the
protected real-model test that authenticates, reads a unique local fixture, and
writes it back through the embedded runtime. Without that credential, only this
protected test is skipped. Signed physical-device execution remains an external
release gate when no device/team is available.
