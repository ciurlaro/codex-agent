@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsString

internal external interface WasmNodeStats : JsAny {
    fun isFile(): Boolean
    fun isDirectory(): Boolean
    fun isSymbolicLink(): Boolean
}

internal external interface WasmNodeHash : JsAny {
    fun update(value: JsAny): WasmNodeHash
    fun digest(encoding: String): String
}

internal fun wasmFsRealpathSync(path: String): String = realpathSync(path)
internal fun wasmFsStatSync(path: String): WasmNodeStats = statSync(path)
internal fun wasmFsLstatSync(path: String): WasmNodeStats = lstatSync(path)
internal fun wasmFsExistsSync(path: String): Boolean = existsSync(path)
internal fun wasmFsSize(path: String): Double = js("statSync(path).size")
internal fun wasmFsAccessSync(path: String, mode: Int): Unit = accessSync(path, mode)
internal fun wasmFsReadFileSync(path: String): JsAny = readFileSync(path)
internal fun wasmFsWriteFileSync(path: String, value: String): Unit = writeFileSync(path, value)
internal fun wasmFsChmodSync(path: String, mode: Int): Unit = chmodSync(path, mode)
internal fun wasmFsMkdtempSync(prefix: String): String = mkdtempSync(prefix)
internal fun wasmFsRmSync(path: String, options: JsAny): Unit = rmSync(path, options)
internal fun wasmFsRenameSync(source: String, destination: String): Unit = renameSync(source, destination)
internal fun wasmFsMkdirRecursive(path: String): Unit = js("mkdirSync(path, { recursive: true })")
internal fun wasmFsListSize(path: String): Int = js("readdirSync(path).length")
internal fun wasmFsListEntry(path: String, index: Int): String = js("readdirSync(path)[index]")
internal fun wasmFsWriteBytes(path: String, bytes: JsAny): Unit = js("writeFileSync(path, bytes)")
internal fun wasmInflateRaw(bytes: JsAny, maxOutputLength: Int): JsAny =
    js("require('node:zlib').inflateRawSync(bytes, { maxOutputLength })")
internal fun wasmPathIsAbsolute(path: String): Boolean = isAbsolute(path)
internal fun wasmPathResolve(path: String): String = resolve(path)
internal fun wasmPathBaseName(path: String): String = basename(path)
internal fun wasmPathDirectoryName(path: String): String = dirname(path)
internal fun wasmPathJoin(parent: String, child: String): String = join(parent, child)
internal fun wasmOsTemporaryDirectory(): String = tmpdir()
internal fun wasmCreateHash(algorithm: String): WasmNodeHash = createHash(algorithm)
internal fun wasmSpawn(
    command: String,
    arguments: JsArray<JsString>,
    options: JsAny,
): JsAny = spawn(command, arguments, options)

internal fun wasmSpawnOptions(workingDirectory: String, detached: Boolean): JsAny =
    js("({ cwd: workingDirectory, detached: detached, shell: false, env: process.env, stdio: ['pipe', 'pipe', 'pipe'] })")

internal fun wasmRemoveOptions(): JsAny = js("({ recursive: true, force: true })")
internal fun wasmProcessPlatform(): String = js("process.platform")
internal fun wasmProcessArchitecture(): String = js("process.arch")
internal fun wasmProcessEnvironment(name: String): String? = js("process.env[name] ?? null")
internal fun wasmProcessArgumentCount(): Int = js("process.argv.length")
internal fun wasmProcessArgument(index: Int): String = js("process.argv[index]")
internal fun wasmProcessExit(code: Int): Unit = js("process.exit(code)")
internal fun wasmConsoleError(message: String): Unit = js("console.error(message)")
internal fun wasmOpenUrl(url: String, platform: String): Unit = js("""
    (() => {
        const command = platform === 'darwin' ? 'open' : platform === 'win32' ? 'explorer.exe' : 'xdg-open';
        const child = require('node:child_process').spawn(command, [url], {
            detached: true,
            shell: false,
            stdio: 'ignore'
        });
        child.once('error', error => console.error(error?.message ?? 'Unable to open the authorization URL'));
        child.unref();
    })()
""")

internal fun wasmChildStdout(child: JsAny): JsAny = js("child.stdout")
internal fun wasmChildStderr(child: JsAny): JsAny = js("child.stderr")
internal fun wasmChildStdin(child: JsAny): JsAny = js("child.stdin")
internal fun wasmChildPid(child: JsAny): Int = js("child.pid")
internal fun wasmChildKill(child: JsAny, signal: String): Unit = js("child.kill(signal)")
internal fun wasmProcessKillGroup(pid: Int, signal: String): Unit = js("process.kill(-pid, signal)")
internal fun wasmStreamPause(stream: JsAny): Unit = js("stream.pause()")
internal fun wasmStreamResume(stream: JsAny): Unit = js("stream.resume()")
internal fun wasmStreamDestroyed(stream: JsAny): Boolean = js("stream.destroyed === true")
internal fun wasmStreamEnd(stream: JsAny): Unit = js("stream.end()")
internal fun wasmBufferLength(value: JsAny): Int = js("value.length")
internal fun wasmBufferByte(value: JsAny, index: Int): Int = js("value[index]")
internal fun wasmBufferAllocate(size: Int): JsAny = js("Buffer.alloc(size)")
internal fun wasmBufferSet(value: JsAny, index: Int, byte: Int): Unit = js("value[index] = byte")
internal fun wasmCloseCode(value: JsAny?): Int = js("typeof value === 'number' ? value : -1")
internal fun wasmErrorMessage(value: JsAny?, fallback: String): String =
    js("value?.message?.toString() ?? fallback")

internal fun wasmOnce(target: JsAny, event: String, callback: (JsAny?) -> Unit): Unit =
    js("target.once(event, callback)")

internal fun wasmOn(target: JsAny, event: String, callback: (JsAny?) -> Unit): Unit =
    js("target.on(event, callback)")

internal fun wasmStreamWrite(
    stream: JsAny,
    value: String,
    callback: (JsAny?) -> Unit,
): Unit = js("stream.write(value, 'utf8', callback)")

internal fun wasmSetTimeout(callback: () -> Unit, delayMillis: Int): JsAny =
    js("setTimeout(callback, delayMillis)")

internal fun wasmClearTimeout(timer: JsAny): Unit = js("clearTimeout(timer)")
