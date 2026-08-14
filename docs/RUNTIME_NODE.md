# Node runtimes

`codex-agent-runtime-node` provides a local Codex App Server runtime to both
Kotlin/JS and Kotlin/WasmJS applications running on Node.js. It implements the
existing `CodexRuntimeFactory` boundary; the shared client remains the only
owner of the protocol handshake.

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

Extract the matching classifier. It contains the App Server and the process
supervisor built for that same host. Pass canonical absolute paths, the
supervisor's SHA-256, and the workspace:

```kotlin
val factory = NodeCodexRuntimeFactory(
    NodeCodexRuntimeConfiguration(
        appServerExecutable = appServerPath.toPath(),
        processSupervisorExecutable = supervisorPath.toPath(),
        processSupervisorSha256 = supervisorSha256,
        workingDirectory = workspacePath.toPath(),
    ),
)
val client = CodexAgentClient(runtimeFactory = factory)
```

`processSupervisorExecutable` and `processSupervisorSha256` are required on
every supported host. There is no separate Windows supervisor classifier.

## Security and lifecycle

Before starting, the runtime requires canonical absolute paths, rejects
symbolic links, verifies the workspace and file names, and checks the packaged
binary identities against the pinned distribution manifest.

The supervisor launches only the configured App Server and owns its complete
process tree. Closing or restarting the runtime therefore cannot leave an App
Server child behind. Newline-delimited JSON is forwarded to the shared
`AppServerConnection`, which remains the sole initialize/initialized handshake
owner.

The runtime never downloads, discovers, installs, or updates an executable. It
accepts no arbitrary command, arguments, shell, Git, build-tool, gateway,
remote workspace, cloud runtime, or general process configuration.

Authentication remains owned by the Codex App Server. The Node adapters neither
receive nor store OAuth tokens and do not require `OPENAI_API_KEY`.

## Release evidence

One portable evidence bundle is reused by a five-host GitHub Actions matrix.
Every host executes the native desktop, JVM, Kotlin/JS-on-Node, and
Kotlin/WasmJS-on-Node lifecycle checks with its matching classifier. Each report
binds the candidate commit, actual OS and architecture, exact compiled runners,
classifier ZIP, App Server, supervisor, and test outcomes.

Linux Arm64 is compiled on a supported x64 host and executed on the real Arm64
runner. Candidate assembly downloads the completed matrix evidence and does not
repeat the host smokes.
