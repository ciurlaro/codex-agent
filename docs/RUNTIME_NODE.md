# Node runtimes

`codex-agent-runtime-node` provides a local Codex App Server runtime to both
Kotlin/JS and Kotlin/WasmJS applications running on Node.js. It implements the
existing `CodexRuntimeFactory` boundary. Applications use the public
`CodexHost` -> `CodexAgent` -> `CodexConversation` lifecycle; the raw runtime
and protocol handshake remain internal.

It is a Kotlin Maven dependency, not an npm JavaScript API. Browser JavaScript,
browser Wasm, and WASI are unsupported.

## Supported hosts

| Node host | Distribution classifier |
| --- | --- |
| macOS Arm64 | `app-server-macos-arm64` |
| macOS x64 | `app-server-macos-x64` |
| Linux Arm64 | `app-server-linux-arm64` |
| Linux x64 | `app-server-linux-x64` |
| Windows x64 | `app-server-windows-x64` |

Release evidence uses Node.js `24.18.0`. Other processor and operating-system
combinations are rejected.

## Configuration

Add the client and runtime to a Kotlin/JS or Kotlin/WasmJS Node application:

```kotlin
dependencies {
    implementation("io.github.ciurlaro:codex-agent-client:0.2.0")
    implementation("io.github.ciurlaro:codex-agent-runtime-node:0.2.0")
}
```

Ship the matching classifier in a bundle directory. It contains the App Server,
the process supervisor, licenses, and an internal manifest for that host. The
platform adapter verifies and atomically installs it into a versioned data
cache, persists the selected workspace, and repairs invalid cached files:

```kotlin
val platform = NodeCodexPlatform(bundleDirectory.toPath(), dataDirectory.toPath())
val codex = CodexHost(
    platform,
    CodexClientInfo("com.example.app", "Example App", appVersion),
)
codex.start()
```

`CodexClientInfo` is the embedding application's identity sent to App Server;
it is not synthesized from the Codex Agent artifact version. Node advertises
all six `CodexRuntimeFeature` values, and the ready agent rejects an operation
before RPC if a custom runtime omits its required feature.

There is no separate public Node operational layer or Windows supervisor
classifier; applications use `NodeCodexPlatform` with `CodexHost`.

## Security and lifecycle

Before starting, the runtime requires canonical absolute paths, rejects
symbolic links, verifies the workspace and file names, and checks the packaged
binary identities against the pinned distribution manifest.

The supervisor launches only the configured App Server and owns its complete
process tree. Closing or restarting the runtime therefore cannot leave an App
Server child behind. Newline-delimited JSON is forwarded to the internal
connection owned by the prepared `CodexAgent`, which performs the sole
initialize/initialized handshake.

The runtime never downloads an executable or resolves a latest version. A host
updates by shipping a classifier for the newer library version; versioned
caches coexist. It accepts no arbitrary command, arguments, shell, gateway,
remote workspace, cloud runtime, or general process configuration.

Validated authorization URLs open with a direct `open`, `xdg-open`, or
`explorer.exe` child process using `shell=false`. Host authentication and state
behave the same as on the other targets.

Authentication remains owned by the Codex App Server. The Node adapters neither
receive nor store OAuth tokens and do not require `OPENAI_API_KEY`.

## Release evidence

One portable evidence bundle is reused by a five-host GitHub Actions matrix.
Every host executes the native desktop, JVM, Kotlin/JS-on-Node, and
Kotlin/WasmJS-on-Node lifecycle checks through the bundle installer with its
matching classifier. Each report
binds the candidate commit, actual OS and architecture, exact compiled runners,
classifier ZIP, App Server, supervisor, and test outcomes.

Linux Arm64 is compiled on a supported x64 host and executed on the real Arm64
runner. Candidate assembly downloads the completed matrix evidence and does not
repeat the host smokes.
