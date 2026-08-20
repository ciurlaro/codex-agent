@file:kotlin.js.JsModule("node:child_process")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.codex_agent_labs.codexmobile.appserver.runtime

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsString

internal external fun spawn(
    command: String,
    arguments: JsArray<JsString>,
    options: JsAny,
): JsAny
