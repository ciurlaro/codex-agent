# Third-party notices

`codex-agent-runtime-android` packages and `codex-agent-runtime-ios` statically
embeds Codex App Server `0.145.0`, built from OpenAI Codex revision
`25af12f7e61572b0bc18ddb1008be543b91519b0` and licensed under Apache-2.0. The
iOS source archive SHA-256 is
`42f627a7b32db41582c73a8eafd9ec4b35d6c3ff81bd3d4455cfd6224d79d329`.
The upstream licence and notice are included in the Android AAR and staged
Apple package as `openai-codex-LICENSE.txt` and `openai-codex-NOTICE.txt`.

The iOS bridge applies the checked-in, iOS-scoped adapter under
`codex-agent-runtime-ios/native/patches`. It exposes the upstream uninitialized
in-process host, removes the disabled V8 code-mode dependency for iOS, and uses
an in-process filesystem environment whose process backend always returns an
unsupported-capability error. Non-iOS upstream behavior is unchanged.

The published artifacts also depend on Kotlin and kotlinx libraries, AndroidX,
and Okio, which are licensed under Apache-2.0. The embedded Rust dependency set
is fixed by the pinned upstream `Cargo.lock` and Rust `1.95.0`; Kotlin
dependency versions are pinned in `gradle/libs.versions.toml` and Gradle
lockfiles.
