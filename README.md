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
  workspace tools to the selected sandbox or security-scoped folder.
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

Desktop and Node applications ship exactly one matching classifier per host:

- `app-server-macos-arm64`
- `app-server-macos-x64`
- `app-server-linux-arm64`
- `app-server-linux-x64`
- `app-server-windows-x64`

Each classifier ZIP contains the pinned App Server, its matching process
supervisor, licenses, and a strict internal runtime manifest. Point the platform
support at the directory containing the ZIP; it selects the current target,
verifies every member, installs it atomically into the versioned data cache, and
repairs a corrupt cache before constructing the runtime:

```kotlin
val platform = DesktopCodexPlatformSupport(
    bundleDirectory = bundledClassifiers.toPath(),
    dataDirectory = appData.toPath(),
)
val selected = platform.workspaces.select(CodexPathWorkspaceSelection(workspacePath))
    as CodexWorkspaceResolution.Available
val prepared = platform.prepare(selected.workspace)
val client = prepared.createClient(clientVersion = "0.2.0")
```

Kotlin/JS and Kotlin/WasmJS applications on Node use the equivalent Node
support:

```kotlin
val platform = NodeCodexPlatformSupport(bundledClassifiers.toPath(), appData.toPath())
```

The libraries do not use an update feed or network downloader. An application
updates the runtime by shipping the classifier for a newer library version; the
installer keeps versioned caches side by side. Existing low-level desktop and
Node factories remain available for applications that already manage verified
paths. Neither layer accepts arbitrary arguments, commands, shells, or remote
transports.

Android hosts keep the bundled executable extractable so it can be verified and
launched by path:

```kotlin
android { packaging { jniLibs.useLegacyPackaging = true } }
```

Android hosts provide `AndroidCodexPlatformSupport(context)`. The host owns the
folder picker and storage permission flow; the library persists and revalidates
canonical shared-storage paths, rejects `Android/data` and `Android/obb`, and
does not request all-files access.

An iOS host may select either an application-container folder or a folder URL
returned by its document picker. `IosCodexPlatformSupport` persists a
security-scoped bookmark, restores and leases it for the runtime, coordinates
file access, and requests reselection when the bookmark is stale or revoked.
Codex home and credentials always stay inside the application sandbox. The
existing low-level factory remains available:

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
Swift Package. Its optional authentication product exposes
`CodexWebAuthenticationBrowser`, an `ASWebAuthenticationSession` presenter for
any validated `CodexAuthorizationUrl`, while retaining
`CodexChatGPTAuthenticationSession`. The App Server owns PKCE, callback
handling, tokens, refresh, and completion events; wrappers do not receive or
store OAuth tokens.

Every runtime platform exposes a native browser through `CodexPlatformSupport`:
Android Custom Tabs, Apple `ASWebAuthenticationSession` (when injected from the
Swift helper), JVM `Desktop.browse`, macOS `NSWorkspace`, Linux `xdg-open`,
Windows `ShellExecuteW`, and Node's direct `open`/`xdg-open`/`explorer.exe`
spawn. Use `CodexAuthorizationUrl.chatGpt` for the strict OpenAI/ChatGPT HTTPS
policy and `CodexAuthorizationUrl.external` for connector, MCP OAuth, and
elicitation URLs (HTTPS or loopback HTTP only). `AgentAuthenticationSession`
shares browser presentation and authentication state on every Kotlin target.

`CodexAgentClient.events` is multicast: every active collector receives the
same ordered events through its own bounded mailbox, so a slow UI observer does
not consume or block events intended for authentication or another observer.

Applications that do not need to assemble those pieces manually can use the
shared lifecycle layer on every Kotlin target:

```kotlin
val host = CodexHostSession(platform, applicationScope, clientVersion = "0.2.0")
host.start()

if (host.state.value.status == CodexHostStatus.WORKSPACE_REQUIRED) {
    host.selectWorkspace(selectionFromThePlatformPicker)
}

val conversation = host.openConversation()
conversation.state.collect { state -> render(state) }
```

`CodexHostSession` owns one prepared client, authentication, the durable queue
of approvals and elicitations, one MCP OAuth attempt, and at most one active
conversation. `AgentConversationSession` combines live text, reasoning, plan,
shell, work, and hook updates, then reconciles with one canonical history read
after completion. `AgentInteractionSession` keeps requests renderable for any
number of observers instead of letting one event collector consume them. MCP
browser dismissal remains separate from authorization completion because the
App Server protocol has no OAuth-cancel route. The low-level client remains
available for applications that intentionally need a different ownership model.

Swift applications get the same reducers through the existing
`CodexAgentAuthentication` product. `CodexHostCoordinator` supplies
main-actor async operations and a newest-value `AsyncStream` without duplicating
state transitions in Swift:

```swift
let host = CodexHostCoordinator(
    sandboxRootPath: sandbox.path,
    clientVersion: "0.2.0"
)

Task { @MainActor in
    for await snapshot in host.states {
        render(snapshot)
    }
}

try await host.start()
// After WORKSPACE_REQUIRED, pass the document-picker URL:
try await host.selectWorkspace(folderURL)
try await host.openConversation()
```

## Capability boundary

The process runtimes launch only the verified App Server from their exact local
classifier. They expose no arbitrary process configuration or remote runtime.

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
