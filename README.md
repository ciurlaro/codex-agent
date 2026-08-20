# Codex Agent

Codex Agent is a reusable Kotlin Multiplatform host for the Codex App Server.
Applications construct one `CodexHost` with an official platform adapter. A
ready host supplies a `CodexAgent`, and the agent opens one active
`CodexConversation`. These three objects expose their own immutable observable
state and follow the runtime, agent, and conversation lifetimes respectively.

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

- `codex-agent-client` contains `CodexHost`, `CodexAgent`,
  `CodexConversation`, their public domain model, and the narrow runtime and
  extension contracts used by platform adapters.
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
implementation("io.github.codex-agent-labs:codex-agent-client:0.2.0")
implementation("io.github.codex-agent-labs:codex-agent-runtime-android:0.2.0")
implementation("io.github.codex-agent-labs:codex-agent-runtime-ios:0.2.0")
implementation("io.github.codex-agent-labs:codex-agent-runtime-desktop:0.2.0")
implementation("io.github.codex-agent-labs:codex-agent-runtime-node:0.2.0")
```

Version `0.2.0` has not yet been tagged or published.

## Storage

Every official platform adapter accepts `storageRoots: CodexStorageRoots? =
null`. `null` uses the platform defaults below; an explicit value overrides
them, and `CodexStorageRoots()` disables library persistence.

| Platform | Cache root | State root |
| --- | --- | --- |
| Android | `cacheDir/codex-agent` | `noBackupFilesDir/codex-agent` |
| iOS | `Library/Caches/CodexAgent` | configured Application Support `codexHomePath` |
| Desktop | `dataDirectory/cache` | `dataDirectory/state` |
| Node | `dataDirectory/cache` | `dataDirectory/state` |

`CodexStorageArea` documents the stable subdirectories for plugin cache, shell
transcripts, and turn-input metadata. Platform-derived absolute paths remain
runtime configuration rather than enum constants.

## Packaged desktop runtimes

Desktop and Node applications ship exactly one matching classifier per host:

- `app-server-macos-arm64`
- `app-server-macos-x64`
- `app-server-linux-arm64`
- `app-server-linux-x64`
- `app-server-windows-x64`

Each classifier ZIP contains the pinned App Server, its matching process
supervisor, licenses, and a strict internal runtime manifest. Point the platform
adapter at the directory containing the ZIP; it selects the current target,
verifies every member, installs it atomically into the versioned data cache, and
repairs a corrupt cache before starting the host:

```kotlin
val platform = DesktopCodexPlatform(
    bundleDirectory = bundledClassifiers.toPath(),
    dataDirectory = appData.toPath(),
)
val clientInfo = CodexClientInfo(
    name = "com.example.app",
    title = "Example App",
    version = appVersion,
)
val codex = CodexHost(platform, clientInfo)
codex.start()
```

Kotlin/JS and Kotlin/WasmJS applications on Node use the equivalent Node
support:

```kotlin
val platform = NodeCodexPlatform(bundledClassifiers.toPath(), appData.toPath())
```

The libraries do not use an update feed or network downloader. An application
updates the runtime by shipping the classifier for a newer library version; the
installer keeps versioned caches side by side. Process launch, verification,
and installation plumbing remains internal. Neither adapter accepts arbitrary
arguments, commands, shells, or remote transports.

Android hosts keep the bundled executable extractable so it can be verified and
launched by path:

```kotlin
android { packaging { jniLibs.useLegacyPackaging = true } }
```

Android hosts provide `AndroidCodexPlatform(context)`. The application owns the
folder picker and permission presentation; the platform adapter persists and
revalidates selected canonical paths, rejects `Android/data` and `Android/obb`,
and does not request all-files access.

An iOS host may select either an application-container folder or a folder URL
returned by its document picker. `IosCodexPlatform` persists a
security-scoped bookmark, restores and leases it for the runtime, coordinates
file access, and requests reselection when the bookmark is stale or revoked.
Codex home and credentials always stay inside the application sandbox:

```kotlin
val platform = IosCodexPlatform(
    sandboxRootPath = sandbox,
    credentialProtection = IosCodexCredentialProtection.WHEN_UNLOCKED,
)
val codex = CodexHost(
    platform,
    CodexClientInfo("com.example.app", "Example App", appVersion),
)
```

The Apple distribution also contains a static `CodexAgent.xcframework` and a
Swift Package. Its optional authentication product exposes only
`CodexWebAuthenticationBrowser`, an `ASWebAuthenticationSession` presenter for
validated `CodexAuthorizationUrl` values. The App Server owns PKCE, callback
handling, tokens, refresh, and completion events; the adapter does not receive
or store OAuth tokens.

Every runtime platform exposes a native browser through `CodexPlatform`:
Android Custom Tabs, Apple `ASWebAuthenticationSession` (when injected from the
Swift helper), JVM `Desktop.browse`, macOS `NSWorkspace`, Linux `xdg-open`,
Windows `ShellExecuteW`, and Node's direct `open`/`xdg-open`/`explorer.exe`
spawn. Use `CodexAuthorizationUrl.chatGpt` for the strict OpenAI/ChatGPT HTTPS
policy and `CodexAuthorizationUrl.external` for connector, MCP OAuth, and
elicitation URLs (HTTPS or loopback HTTP only). The ready `CodexAgent`
coordinates browser presentation and authorization state on every Kotlin
target.

Custom targets implement `CodexPlatform` and return a `PreparedCodexRuntime`.
`CodexRuntime`, `CodexRuntimeFactory`, and the bounded `JsonLineFramer` exist
only to construct that adapter; `AppServerProtocolIdentity` supplies the three
values needed to reject an incompatible runtime. None of these support types
exposes App Server operations independently of `CodexAgent`.
Every prepared runtime must also declare its exact `CodexRuntimeFeature` set;
there is no permissive default that could advertise an operation the runtime
cannot actually perform.

Applications that do not need to assemble those pieces manually can use the
shared lifecycle layer on every Kotlin target:

```kotlin
val clientInfo = CodexClientInfo("com.example.app", "Example App", appVersion)
val host = CodexHost(platform, clientInfo)
applicationScope.launch { host.state.collect(::render) }
host.start()

when (val state = host.state.value) {
    is CodexHostState.Ready -> {
        val agent = state.agent
        val conversation = agent.openConversation()
        conversation.send("Hello")
    }
    is CodexHostState.WorkspaceRequired -> {
        host.selectWorkspace(selectionFromThePlatformPicker)
    }
    is CodexHostState.Failed -> showStartupError(state.failure)
    else -> showStartupProgress()
}
```

The two-argument Host constructor owns a supervised default coroutine scope and
cancels it on `close()`. Applications that need their own parent job may use
`CodexHost(platform, applicationScope, clientInfo)` instead. `CodexClientInfo`
identifies the embedding application in the App Server initialize request; it
is deliberately not the Codex Agent library version.

`CodexHost` owns workspace/runtime startup and shutdown. `CodexAgent` owns
authentication, approvals and elicitations, connector and MCP authorization,
backend catalogs, and at most one active conversation. `CodexConversation`
owns live text, reasoning, plan, shell, work, and hook updates, then reconciles
with canonical history after completion. The raw client, connection, generated
protocol, and workflow controllers remain internal.

Swift applications receive the same host, ready agent, conversation handles,
and immutable state through the `CodexAgent` framework. The
`CodexAgentObservation` SwiftPM product adds cancellation-safe typed
`AsyncStream` properties such as `host.states`, `agent.authenticationStates`,
`agent.activeConversations`, and `conversation.states`.
`CodexAgentSwiftSupport` adds only the natural default calls
`agent.authenticate()`, `agent.openConversation()`, and
`conversation.send(_:)`, plus `Error.codexFailure` for stable code, message,
and recoverability. Advanced Kotlin overloads remain available as native Swift
`async throws` operations.
`CodexAgentAuthentication` contributes only the native browser adapter; it does
not introduce a second host, client, event broadcaster, authentication session,
or reducer.

## Runtime features and failures

`CodexAgent.features` is the immutable feature set prepared by the selected
runtime. Android, Desktop, and Node advertise shell commands, skills, hooks,
plugins, connectors, and MCP servers. The embedded iOS runtime advertises only
skills; its sandboxed built-in file tools are runtime implementation details.
Calling an unavailable capability fails before an RPC with
`CodexOperationException` and a non-recoverable `CodexFailure` whose code is
`unsupported_feature`.

Host, authentication, interaction, integration-authorization, and conversation
state expose `CodexFailure` instead of unrelated error strings. Conversation
state also supplies `canStartTurn`, `canReload`, and `canCancelTurn`, and reaches
the terminal `CLOSED` status when its owner replaces or closes the handle.

## Capability boundary

The process runtimes launch only the verified App Server from their exact local
classifier. They expose no arbitrary process configuration or remote runtime.

The iOS runtime additionally limits built-in tools to sandboxed file reads,
directory listing, text search, atomic writes, and workspace-confined patches.
Model API network access remains available through the App Server.

## Release evidence

An unlabeled pull request runs only lightweight workflow and impact checks.
Adding `merge-ready` validates the prospective merge tree and runs only its
affected product, platform, and consumer lanes. Successful lanes from an
earlier run of the same pull request may be reused; `ci:full` may add work but
never suppresses required work. A merge group reuses the pull-request result
when its Git tree is identical, otherwise it reevaluates the changed base and
runs the newly affected lanes.

A push to `main` only promotes equal-tree validation receipts, their exact
GitHub-hosted artifacts, and selected cache seeds; it never compiles or tests.
A protected `candidate/v<version>-rc.N` tag consumes those promoted bytes and
performs verification, signing, sidecar generation, and release assembly only.
Publication consumes the exact candidate bytes. Protected environments hold
the Android evidence, signing, and publication authority.

Git commit, tree, and blob identities determine whether inputs changed.
Checksums remain only where a format or integrity boundary requires them; they
never decide whether source changed, whether a lane must run, or whether two
independent builds are equivalent.

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
