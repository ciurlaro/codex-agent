@file:kotlin.js.JsModule("node:zlib")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.js.JsAny

internal external fun inflateRawSync(bytes: JsAny, options: JsAny): JsAny
