# Codex Agent

Reusable Kotlin Multiplatform client and Android runtime for the Codex App
Server.

## Modules

- `codex-agent-client` contains the portable agent, App Server client, generated
  protocol, and `CodexRuntimeFactory` dependency-injection contract. It targets
  Android, JVM, iOS Arm64, and iOS Simulator Arm64.
- `codex-agent-runtime-android` contains the verified Android App Server binary,
  process runtime, loopback proxy, certificate preparation, and SQLite privacy
  guard. Hosts construct `AndroidCodexRuntimeFactory(context)`.
- `codex-agent-runtime-ios` is reserved for a future native runtime. It is not
  implemented or published.

The runtime boundary is dependency injection; no `expect`/`actual` runtime
factory is used.

## Coordinates

```kotlin
implementation("io.github.ciurlaro:codex-agent-client:0.1.0")
implementation("io.github.ciurlaro:codex-agent-runtime-android:0.1.0")
```

Android hosts must keep the bundled executable extracted so the runtime can
verify and launch it by path:

```kotlin
android { packaging { jniLibs.useLegacyPackaging = true } }
```

## Verification

```shell
./gradlew -p buildSrc test
./gradlew verifyRepository
./gradlew :codex-agent-client:compileKotlinIosArm64 \
  :codex-agent-client:compileKotlinIosSimulatorArm64
./gradlew :codex-agent-runtime-android:connectedDebugAndroidTest
```

The iOS compilation gate is enforced by macOS CI. The runtime device test
requires an ARM64 Android host or emulator.
See [protocol provenance](docs/PROTOCOL.md) and the
[release procedure](docs/RELEASING.md).

## License

Codex Agent is licensed under GPL-3.0-or-later. The bundled Codex App Server is
licensed separately under Apache-2.0; see [third-party notices](THIRD_PARTY_NOTICES.md).
