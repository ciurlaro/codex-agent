# Codex Agent

Reusable Kotlin Multiplatform client with local Android and iOS runtimes for
the Codex App Server.

## Modules

- `codex-agent-client` contains the portable agent, App Server client, generated
  protocol, and `CodexRuntimeFactory` dependency-injection contract. It targets
  Android, JVM, iOS Arm64, and iOS Simulator Arm64.
- `codex-agent-runtime-android` contains the verified Android App Server binary,
  process runtime, loopback proxy, certificate preparation, and SQLite privacy
  guard. Hosts construct `AndroidCodexRuntimeFactory(context)`.
- `codex-agent-runtime-ios` embeds the pinned Rust App Server in-process for
  iPhoneOS and Apple Silicon Simulator. Hosts inject
  `IosCodexRuntimeFactory(configuration)` with an explicit sandbox-local
  workspace.

The runtime boundary is dependency injection; no `expect`/`actual` runtime
factory is used.

## Coordinates

```kotlin
implementation("io.github.ciurlaro:codex-agent-client:0.2.0")
implementation("io.github.ciurlaro:codex-agent-runtime-android:0.2.0")
implementation("io.github.ciurlaro:codex-agent-runtime-ios:0.2.0")
```

Android hosts must keep the bundled executable extracted so the runtime can
verify and launch it by path:

```kotlin
android { packaging { jniLibs.useLegacyPackaging = true } }
```

An iOS host creates a sandbox-local workspace and injects the runtime without
an `expect`/`actual` factory:

```kotlin
val configuration = IosCodexRuntimeConfiguration(
    sandboxRootPath = sandbox,
    workspacePath = "$sandbox/Documents/CodexWorkspace",
)
val factory = IosCodexRuntimeFactory(configuration)
val client = CodexAgentClient(
    runtimeFactory = factory,
    clientVersion = "0.2.0",
    builtInToolDispatcher = factory.workspaceTools,
)
client.authenticate(CodexAuthenticationMethod.ApiKey(apiKey))
```

Apple applications can instead consume the staged `CodexAgent` Swift Package,
which contains one static `CodexAgent.xcframework` exporting the client and iOS
runtime. `IosCodexAgentFacade` provides construction, API-key/device-code
authentication, event observation, cancellation, and deterministic close.

The iOS capability profile advertises only sandboxed file read, directory list,
text search, atomic file write, and workspace-confined `apply_patch` tools. The
patch parser and applicator reuse pinned upstream Rust code. Shells, arbitrary
processes, Git, build tools, hooks, apps/plugins, and process-based MCP are
unavailable and are not advertised. Model API network access remains available.

## What shipped

The current `0.2.0` implementation includes:

- a genuinely local iOS runtime for iOS Arm64 and Apple Silicon Simulator,
  embedding the pinned Codex App Server as a static in-process Rust library;
- one shared Kotlin client and JSON-RPC protocol implementation across Android
  and iOS, with `AppServerConnection` remaining the sole handshake owner;
- sandbox-local workspace and conversation state, plus bounded in-process file
  read, directory list, text search, atomic write, and `apply_patch` tools;
- API-key and device-code authentication in addition to the existing browser
  authentication default;
- deterministic start, close, and restart behavior;
- a static `CodexAgent.xcframework`, local Swift Package, minimal Swift facade,
  and standalone Swift test application;
- verified device and simulator compilation/linking, Rust bridge tests,
  Kotlin/Native lifecycle tests, XCFramework creation, and clean Swift Package
  consumption. Existing Android and JVM verification still passes.

## What did not ship

The iOS runtime intentionally does not include a remote runtime or workspace,
gateway, WebSocket proxy, child Codex process, arbitrary command or shell
execution, native Git, build tools, process hooks, apps/plugins, downloaded
executables, or process-based MCP servers. Files-provider and security-scoped
folder support are also out of scope; the first version requires an explicit
workspace inside the application sandbox.

Two external execution gates remain unproven: the protected real-model
Simulator test was not run because `OPENAI_API_KEY` was unavailable, and a
physical-device launch was not run because no signed device was available.
Physical-device compilation and linking did pass. Version `0.2.0` has not been
tagged or published; the XCFramework, package archive, and Maven publications
produced so far are local build artifacts.

## Verification

```shell
./gradlew -p buildSrc test
./gradlew verifyRepository
./gradlew :codex-agent-client:compileKotlinIosArm64 \
  :codex-agent-client:compileKotlinIosSimulatorArm64
./gradlew :codex-agent-runtime-android:connectedDebugAndroidTest
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew verifyIosRuntime
```

The iOS gate builds and links device/simulator binaries, runs the embedded
runtime on Simulator, creates the XCFramework/Swift Package zip, and builds a
clean standalone Swift app. If `OPENAI_API_KEY` is present, it additionally
runs the protected real-model local workspace test. Physical-device execution
requires signing and a connected device; compilation and linking do not.
See [protocol provenance](docs/PROTOCOL.md) and the
[iOS runtime design](docs/RUNTIME_IOS.md) and
[release procedure](docs/RELEASING.md).

## License

Codex Agent is licensed under GPL-3.0-or-later. The bundled Codex App Server is
licensed separately under Apache-2.0; see [third-party notices](THIRD_PARTY_NOTICES.md).
