@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.ciurlaro.codexmobile.appserver.runtime

import codex_desktop.codex_make_executable
import okio.Path

internal actual fun makeDesktopExecutable(path: Path) {
    check(codex_make_executable(path.toString()) == 0) { "Unable to make '${path.name}' executable" }
}
