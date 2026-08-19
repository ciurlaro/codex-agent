@file:kotlin.js.JsModule("node:fs")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.ciurlaro.codexmobile.agent

import kotlin.js.JsAny

@JsName("statSync")
internal external fun nodeStatSync(path: String): NodeFileStats

internal external interface NodeFileStats : JsAny {
    val size: Double

    fun isFile(): Boolean
}

@JsName("readFileSync")
internal external fun nodeReadFileSync(path: String, encoding: String): String

@JsName("readFileSync")
internal external fun nodeReadBufferSync(path: String): JsAny

@JsName("writeFileSync")
internal external fun nodeWriteFileSync(path: String, value: String, encoding: String)

@JsName("mkdirSync")
internal external fun nodeMkdirSync(path: String, options: JsAny)

@JsName("renameSync")
internal external fun nodeRenameSync(source: String, destination: String)

@JsName("rmSync")
internal external fun nodeRmSync(path: String, options: JsAny)
