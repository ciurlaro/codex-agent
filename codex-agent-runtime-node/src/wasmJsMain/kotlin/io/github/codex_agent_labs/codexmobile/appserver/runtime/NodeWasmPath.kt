@file:kotlin.js.JsModule("node:path")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.codex_agent_labs.codexmobile.appserver.runtime

internal external fun isAbsolute(path: String): Boolean
internal external fun resolve(path: String): String
internal external fun basename(path: String): String
internal external fun dirname(path: String): String
internal external fun join(parent: String, child: String): String
