@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.native.CpuArchitecture
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okio.FileSystem

internal actual val desktopProcessDispatcher: CoroutineDispatcher = Dispatchers.Default
internal actual val desktopFileSystem: FileSystem = FileSystem.SYSTEM

internal actual fun currentDesktopTarget(): String = when (
    Platform.osFamily to Platform.cpuArchitecture
) {
    OsFamily.MACOSX to CpuArchitecture.ARM64 -> "macosArm64"
    OsFamily.MACOSX to CpuArchitecture.X64 -> "macosX64"
    OsFamily.LINUX to CpuArchitecture.ARM64 -> "linuxArm64"
    OsFamily.LINUX to CpuArchitecture.X64 -> "linuxX64"
    OsFamily.WINDOWS to CpuArchitecture.X64 -> "mingwX64"
    else -> error("Unsupported desktop target: ${Platform.osFamily}/${Platform.cpuArchitecture}")
}
