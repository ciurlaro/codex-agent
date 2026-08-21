@file:kotlin.js.JsModule("node:os")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.codex_agent_labs.codexmobile.appserver.runtime

internal external fun tmpdir(): String
