# Node runtime

`codex-agent-runtime-node` is the local runtime for Kotlin/JS applications
running on Node.js. It implements the existing `CodexRuntimeFactory` boundary;
the shared client and protocol are unchanged.

It is not an npm JavaScript API. Version `0.2.0` is consumed as a Kotlin/JS
Maven dependency by Kotlin Multiplatform or Kotlin/JS projects.

## Supported hosts

The runtime supports exactly the same native App Server distributions as the
desktop runtime:

| Node host | Distribution classifier |
| --- | --- |
| macOS Arm64 | `app-server-macos-arm64` |
| macOS x64 | `app-server-macos-x64` |
| Linux Arm64 | `app-server-linux-arm64` |
| Linux x64 | `app-server-linux-x64` |
| Windows x64 | `app-server-windows-x64` |

Node.js is pinned to `24.18.0` for release evidence. Other processor and
operating-system combinations are rejected.

## Configuration

Add the client and Node runtime to a Kotlin/JS Node application:

```kotlin
dependencies {
    implementation("io.github.ciurlaro:codex-agent-client:0.2.0")
    implementation("io.github.ciurlaro:codex-agent-runtime-node:0.2.0")
}
```

Extract the matching App Server classifier yourself, then pass absolute paths:

```kotlin
val factory = NodeCodexRuntimeFactory(
    NodeCodexRuntimeConfiguration(
        appServerExecutable = executablePath.toPath(),
        workingDirectory = workspacePath.toPath(),
        windowsSupervisorExecutable = windowsSupervisorPath?.toPath(),
    ),
)
val client = CodexAgentClient(runtimeFactory = factory)
```

`windowsSupervisorExecutable` must be absent on macOS and Linux. On Windows it
must point to `codex-agent-node-windows-supervisor.exe` extracted from the
runtime's verified `windows-supervisor-x64` classifier.

## Security and lifecycle

Before starting, the runtime requires canonical absolute paths, rejects
symbolic links, checks that the workspace is a directory, checks the executable
name, and verifies the App Server SHA-256 against the pinned distribution
manifest. Windows applies the same checks to the supervisor.

The App Server is launched directly with `shell = false`. macOS and Linux use a
detached process group so close can terminate the complete process tree.
Windows uses the small verified supervisor for the same ownership guarantee.
The runtime forwards newline-delimited JSON between the process and the shared
`AppServerConnection`; that connection remains the sole initialize/initialized
handshake owner.

The runtime never downloads or discovers an executable. It exposes no command,
argument, shell, Git, build-tool, gateway, remote workspace, or general process
configuration. Browser JavaScript and Wasm remain client-only.

Authentication remains owned by the Codex App Server. The Node wrapper neither
receives nor stores OAuth tokens and does not require `OPENAI_API_KEY`.

## Release evidence

The release workflow first builds and verifies the Windows supervisor identity,
then builds one portable Kotlin/JS evidence runner bound to that identity and
executes it on the five real host targets with Node `24.18.0`. Each target
report binds:

- the immutable candidate commit and real runner OS/architecture;
- the exact test class, four methods, and zero skips/failures/errors;
- the compiled Kotlin/JS runner;
- the matching classifier ZIP and App Server binary;
- the Windows supervisor hash when applicable.

Linux Arm64 is compiled on a supported x64 host and executed on the real Arm64
runner without configuring Kotlin/Native there. The candidate consumes all five
external records once; it does not rerun the host smokes during Apple assembly.
