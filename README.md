# Codex Agent

Reusable Kotlin Multiplatform client with local Android, iOS, desktop, and
Kotlin/JS Node runtimes for the Codex App Server.

## Modules

- `codex-agent-client` contains the portable agent, App Server client, generated
  protocol, and `CodexRuntimeFactory` dependency-injection contract. It targets
  Android, JVM, iOS Arm64/Simulator Arm64, macOS Arm64/x64, Linux Arm64/x64,
  Windows x64, JavaScript browser/Node, and WasmJS browser/Node.
- `codex-agent-runtime-android` contains the verified Android App Server binary,
  process runtime, loopback proxy, certificate preparation, and SQLite privacy
  guard. Hosts construct `AndroidCodexRuntimeFactory(context)`.
- `codex-agent-runtime-ios` embeds the pinned Rust App Server in-process for
  iPhoneOS and Apple Silicon Simulator. Hosts inject
  `IosCodexRuntimeFactory(configuration)` with an explicit sandbox-local
  workspace.
- `codex-agent-runtime-desktop` launches an explicitly supplied, hash-verified
  Codex App Server on macOS Arm64/x64, Linux Arm64/x64, and Windows x64.
- `codex-agent-runtime-node` provides the same explicit local-process boundary
  to Kotlin/JS applications running on Node.js on those five host targets.

The runtime boundary is dependency injection; no `expect`/`actual` runtime
factory is used.

## Coordinates

```kotlin
implementation("io.github.ciurlaro:codex-agent-client:0.2.0")
implementation("io.github.ciurlaro:codex-agent-runtime-android:0.2.0")
implementation("io.github.ciurlaro:codex-agent-runtime-ios:0.2.0")
implementation("io.github.ciurlaro:codex-agent-runtime-desktop:0.2.0")
implementation("io.github.ciurlaro:codex-agent-runtime-node:0.2.0")
```

Android hosts must keep the bundled executable extracted so the runtime can
verify and launch it by path:

```kotlin
android { packaging { jniLibs.useLegacyPackaging = true } }
```

Desktop hosts extract the matching `app-server-macos-arm64`,
`app-server-macos-x64`, `app-server-linux-arm64`, `app-server-linux-x64`, or
`app-server-windows-x64` ZIP classifier and pass absolute paths explicitly:

```kotlin
val factory = DesktopCodexRuntimeFactory(
    DesktopCodexRuntimeConfiguration(
        appServerExecutable = executablePath.toPath(),
        workingDirectory = workspacePath.toPath(),
    ),
)
val client = CodexAgentClient(factory)
```

The desktop runtime never downloads or discovers an executable. Its API accepts
no arbitrary command, shell, remote transport, or executable-discovery
configuration.

Kotlin/JS applications running on Node.js use the same explicit executable and
workspace model:

```kotlin
val factory = NodeCodexRuntimeFactory(
    NodeCodexRuntimeConfiguration(
        appServerExecutable = executablePath.toPath(),
        workingDirectory = workspacePath.toPath(),
        windowsSupervisorExecutable = windowsSupervisorPath?.toPath(),
    ),
)
val client = CodexAgentClient(factory)
```

The supervisor argument is required only on Windows and must come from the
verified `windows-supervisor-x64` classifier. Browser JavaScript and Wasm remain
client-only; no remote transport or browser process launcher is added.

An iOS host creates a sandbox-local workspace and injects the runtime without
an `expect`/`actual` factory:

```kotlin
val configuration = IosCodexRuntimeConfiguration(
    sandboxRootPath = sandbox,
    workspacePath = "$sandbox/Documents/CodexWorkspace",
    credentialProtection = IosCodexCredentialProtection.WHEN_UNLOCKED,
)
val factory = IosCodexRuntimeFactory(configuration)
val client = CodexAgentClient(
    runtimeFactory = factory,
    clientVersion = "0.2.0",
    builtInToolDispatcher = factory.workspaceTools,
)
client.authenticate(CodexAuthenticationMethod.ChatGptBrowser)
```

Apple applications can instead consume the staged `CodexAgent` Swift Package,
which contains one static `CodexAgent.xcframework` exporting the client and iOS
runtime. Its additive `CodexAgentAuthentication` product provides
`CodexChatGPTAuthenticationSession`, a reusable `ASWebAuthenticationSession`
presenter around `IosCodexAgentFacade`. Sign-in stays in a secure browser sheet,
shares the user's existing Safari login, and returns automatically to the app.
The embedded App Server retains ownership of PKCE, its localhost callback,
token persistence/refresh, and completion events; the Apple wrapper never
receives or stores OAuth tokens. API-key authentication remains optional and is
not needed for CI, release verification, or the supported end-user flow.
Swift consumers must explicitly `import CodexAgent` and
`import CodexAgentAuthentication`; the authentication product does not
re-export the binary module.

The iOS capability profile advertises only sandboxed file read, directory list,
text search, atomic file write, and workspace-confined `apply_patch` tools. The
patch parser and applicator reuse pinned upstream Rust code. Shells, arbitrary
processes, Git, build tools, hooks, apps/plugins, and process-based MCP are
unavailable and are not advertised. Model API network access remains available.

## What shipped

The current `0.2.0` implementation includes:

- a genuinely local iOS runtime for iOS Arm64 and Apple Silicon Simulator,
  embedding the pinned Codex App Server as a static in-process Rust library;
- one shared Kotlin client and JSON-RPC protocol implementation across Android,
  iOS, desktop, JavaScript, and WasmJS, with `AppServerConnection` remaining
  the sole handshake owner;
- native desktop process runtimes and deterministic licensed Codex 0.145.0
  classifier ZIPs for macOS Arm64/x64, Linux Arm64/x64, and Windows x64;
- a Kotlin/JS Node runtime using those same pinned classifiers, with an explicit
  verified process-tree supervisor on Windows;
- sandbox-local workspace and conversation state, plus bounded in-process file
  read, directory list, text search, atomic write, and `apply_patch` tools;
- seamless ChatGPT browser authentication through `ASWebAuthenticationSession`
  and the existing Codex-managed callback, with API-key authentication
  remaining optional at the shared client level;
- deterministic start, close, and restart behavior;
- a static `CodexAgent.xcframework`, local Swift Package, release-ready remote
  SwiftPM manifest/checksum gate, minimal Swift facade, and standalone Swift
  test application;
- verified device and simulator compilation/linking, Rust bridge tests,
  Kotlin/Native lifecycle tests, bounded workspace tools, XCFramework creation,
  and clean local Swift Package consumption. Existing Android and JVM
  verification still passes.

## What did not ship

The iOS runtime intentionally does not include a remote runtime or workspace,
gateway, WebSocket proxy, child Codex process, arbitrary command or shell
execution, native Git, build tools, process hooks, apps/plugins, downloaded
executables, or process-based MCP servers. Files-provider and security-scoped
folder support are also out of scope; the first version requires an explicit
workspace inside the application sandbox.

Credential-free automation does not claim real-model execution. Real-model
acceptance is an explicit manual test in the Swift app using interactive ChatGPT
browser login; no API key, stored ChatGPT credential, or generated token is used
by CI. The Apple browser sheet itself cannot be exercised by a headless build.
A physical-device launch also remains external when no signing team and device
are available. Physical-device compilation and linking are still required.

Version `0.2.0` has not been tagged or published. Until its immutable
`CodexAgent-0.2.0.xcframework.zip` release asset exists, public URL-based SwiftPM
resolution cannot run; the checked-in manifest, checksum verification, and clean
remote-consumer fixture are release-ready but do not pretend that asset already
exists. No consumer repository is updated by this project.

## Verification

```shell
./gradlew -p buildSrc test
./gradlew verifyRepository
./gradlew :codex-agent-client:compileKotlinIosArm64 \
  :codex-agent-client:compileKotlinIosSimulatorArm64 \
  :codex-agent-client:compileKotlinJs \
  :codex-agent-client:compileKotlinWasmJs
./gradlew :codex-agent-runtime-node:jsNodeTest \
  :codex-agent-runtime-node:packageNodeRuntimeEvidenceRunner
./gradlew :codex-agent-runtime-android:connectedDebugAndroidTest
DEVELOPER_DIR=/Applications/Xcode_26.6.app/Contents/Developer \
  ./gradlew verifyIosRuntime
```

The iOS gate is credential-free. It builds and links device/simulator binaries,
runs the real embedded runtime on Simulator, proves the JSON-RPC handshake and
restart lifecycle, exercises workspace confinement and the exact advertised
tool dispatch set, creates the XCFramework/Swift Package archives, and builds a
clean standalone Swift app. It does not call a model. Follow the manual ChatGPT
browser-login acceptance in [the release procedure](docs/RELEASING.md) to prove
a real model reads and modifies the sandbox workspace. Physical-device
execution requires signing and a connected device; compilation and linking do
not.
See [protocol provenance](docs/PROTOCOL.md) and the
[iOS runtime design](docs/RUNTIME_IOS.md) and
[Node runtime design](docs/RUNTIME_NODE.md) and
[release procedure](docs/RELEASING.md).

## License

Codex Agent is licensed under GPL-3.0-or-later. The bundled Codex App Server is
licensed separately under Apache-2.0; see [third-party notices](THIRD_PARTY_NOTICES.md).
Distribution of the static Apple framework and native runtime classifiers
remains subject to separate, hash-bound GPL approvals. External approvals are
not inferred by automation; missing or invalidated approval blocks
`verifyPublicationReadiness`.
