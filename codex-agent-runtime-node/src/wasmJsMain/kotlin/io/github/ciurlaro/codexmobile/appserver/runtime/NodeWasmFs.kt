@file:kotlin.js.JsModule("node:fs")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.js.JsAny

internal external fun realpathSync(path: String): String
internal external fun statSync(path: String): WasmNodeStats
internal external fun accessSync(path: String, mode: Int)
internal external fun readFileSync(path: String): JsAny
internal external fun writeFileSync(path: String, value: String)
internal external fun chmodSync(path: String, mode: Int)
internal external fun mkdtempSync(prefix: String): String
internal external fun rmSync(path: String, options: JsAny)
