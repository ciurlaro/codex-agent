# Codex Agent

Codex Agent is a reusable Kotlin Multiplatform client that runs the Codex App
Server locally on each supported platform. Applications inject a
`CodexRuntimeFactory`; the shared client remains responsible for the protocol
and the App Server handshake.

## Supported standalone targets

| Application target | Local runtime |
| --- | --- |
| Android | Packaged Android App Server |
| iOS Arm64 and Apple Silicon Simulator | Embedded in-process App Server |
| macOS Arm64/x64, Linux Arm64/x64, Windows x64 | Native desktop runtime |
| JVM desktop on those five hosts | JVM desktop runtime |
| Kotlin/JS on Node.js on those five hosts | JS Node runtime |
| Kotlin/WasmJS on Node.js on those five hosts | WasmJS Node runtime |

Browser JavaScript, browser Wasm, and WASI are not supported execution targets.
The runtime API does not provide remote or cloud execution, a gateway, or a
general-purpose shell.

## Modules

- `codex-agent-client` contains the portable client, generated protocol, and
  `CodexRuntimeFactory` dependency-injection contract.
- `codex-agent-runtime-android` verifies and launches the packaged Android App
  Server with its loopback proxy, certificate preparation, and SQLite privacy
  guard.
- `codex-agent-runtime-ios` embeds the pinned Rust App Server and confines its
  workspace tools to the application sandbox.
- `codex-agent-runtime-desktop` supplies native and JVM desktop adapters for the
  five supported desktop hosts.
- `codex-agent-runtime-node` supplies the same local lifecycle to Kotlin/JS and
  Kotlin/WasmJS applications running on Node.js.

## Coordinates

```kotlin
implementation("io.github.ciurlaro:codex-agent-client:0.2.0")
implementation("io.github.ciurlaro:codex-agent-runtime-android:0.2.0")
implementation("io.github.ciurlaro:codex-agent-runtime-ios:0.2.0")
implementation("io.github.ciurlaro:codex-agent-runtime-desktop:0.2.0")
implementation("io.github.ciurlaro:codex-agent-runtime-node:0.2.0")
```

Version `0.2.0` has not yet been tagged or published.

## Packaged desktop runtimes

Desktop and Node hosts extract exactly one matching classifier:

- `app-server-macos-arm64`
- `app-server-macos-x64`
- `app-server-linux-arm64`
- `app-server-linux-x64`
- `app-server-windows-x64`

Each classifier ZIP contains both the pinned App Server and its matching process
supervisor. Pass absolute paths, the extracted supervisor's SHA-256, and the
workspace:

```kotlin
val factory = DesktopCodexRuntimeFactory(
    DesktopCodexRuntimeConfiguration(
        appServerExecutable = appServerPath.toPath(),
        processSupervisorExecutable = supervisorPath.toPath(),
        processSupervisorSha256 = supervisorSha256,
        workingDirectory = workspacePath.toPath(),
    ),
)
val client = CodexAgentClient(factory)
```

Kotlin/JS and Kotlin/WasmJS applications on Node use the equivalent Node
configuration:

```kotlin
val factory = NodeCodexRuntimeFactory(
    NodeCodexRuntimeConfiguration(
        appServerExecutable = appServerPath.toPath(),
        processSupervisorExecutable = supervisorPath.toPath(),
        processSupervisorSha256 = supervisorSha256,
        workingDirectory = workspacePath.toPath(),
    ),
)
val client = CodexAgentClient(factory)
```

The libraries do not download, discover, install, or update these files. The
configuration accepts no arbitrary executable, arguments, command, shell, or
remote transport. The supervisor exists only to own and reliably stop the App
Server process tree.

Android hosts keep the bundled executable extractable so it can be verified and
launched by path:

```kotlin
android { packaging { jniLibs.useLegacyPackaging = true } }
```

An iOS host instead provides an explicit sandbox-local workspace:

```kotlin
val factory = IosCodexRuntimeFactory(
    IosCodexRuntimeConfiguration(
        sandboxRootPath = sandbox,
        workspacePath = "$sandbox/Documents/CodexWorkspace",
        credentialProtection = IosCodexCredentialProtection.WHEN_UNLOCKED,
    ),
)
val client = CodexAgentClient(
    runtimeFactory = factory,
    clientVersion = "0.2.0",
    builtInToolDispatcher = factory.workspaceTools,
)
```

The Apple distribution also contains a static `CodexAgent.xcframework` and a
Swift Package. Its optional authentication product presents ChatGPT sign-in in
the secure system browser sheet. The App Server owns PKCE, callback handling,
tokens, refresh, and completion events; wrappers do not receive or store OAuth
tokens.

## Capability boundary

The process runtimes launch only the verified App Server from explicit local
paths. They expose no arbitrary process execution, Git, build-tool, remote
workspace, or process-based MCP configuration.

The iOS runtime additionally limits built-in tools to sandboxed file reads,
directory listing, text search, atomic writes, and workspace-confined patches.
Model API network access remains available through the App Server.

## Release evidence

The exact successful `main` CI run packages the portable runners once, then a
five-host matrix executes the native desktop, JVM, JS-on-Node, and
WasmJS-on-Node lifecycle checks against each exact matching classifier. That CI
run also builds the Android APKs and release AAR once. Candidate Firebase Test
Lab evidence uses those imported binaries on an ARM virtual device, so no
connected physical phone is required. Apple host tests and device/simulator
slices run independently; CI verifies and exports their whole distribution
once for candidate import.

A protected candidate is created from a `candidate/v<version>-rc.N` tag on an
exact successful `main` commit. Candidate assembly and publication consume the
recorded CI artifacts rather than rebuilding them. Protected environments hold
signing and publication credentials and require their configured approvals.
Reruns reuse logically matching successful work; they do not require two
independent builds to be byte-identical. Integrity hashes and signatures still
bind every artifact that is actually promoted.

See [protocol provenance](docs/PROTOCOL.md), the
[iOS runtime design](docs/RUNTIME_IOS.md), the
[Node runtime design](docs/RUNTIME_NODE.md), and the
[release procedure](docs/RELEASING.md).

## License

Codex Agent is licensed under GPL-3.0-or-later. The bundled Codex App Server is
licensed separately under Apache-2.0; see
[third-party notices](THIRD_PARTY_NOTICES.md). Distribution of the static Apple
framework and native runtime classifiers remains subject to the repository's
hash-bound GPL approvals.
