@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.ciurlaro.codexmobile.appserver.runtime

import codex_desktop.codex_getenv
import kotlinx.cinterop.toKString

internal actual fun desktopTestEnvironment(name: String): String? = codex_getenv(name)?.toKString()
