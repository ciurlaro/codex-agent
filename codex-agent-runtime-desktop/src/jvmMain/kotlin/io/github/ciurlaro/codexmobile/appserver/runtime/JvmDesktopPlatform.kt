package io.github.ciurlaro.codexmobile.appserver.runtime

import java.util.Locale

internal actual fun currentDesktopTarget(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    val architecture = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
    val arm64 = architecture in setOf("aarch64", "arm64")
    val x64 = architecture in setOf("amd64", "x86_64")
    return when {
        ("mac" in os || "darwin" in os) && arm64 -> "macosArm64"
        ("mac" in os || "darwin" in os) && x64 -> "macosX64"
        "linux" in os && arm64 -> "linuxArm64"
        "linux" in os && x64 -> "linuxX64"
        "windows" in os && x64 -> "mingwX64"
        else -> error("Unsupported desktop target: $os/$architecture")
    }
}
