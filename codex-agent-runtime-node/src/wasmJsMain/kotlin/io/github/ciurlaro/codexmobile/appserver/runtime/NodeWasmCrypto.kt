@file:kotlin.js.JsModule("node:crypto")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.ciurlaro.codexmobile.appserver.runtime

internal external fun createHash(algorithm: String): WasmNodeHash
