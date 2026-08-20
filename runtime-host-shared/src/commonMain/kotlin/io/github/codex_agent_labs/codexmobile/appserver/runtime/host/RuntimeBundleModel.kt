package io.github.codex_agent_labs.codexmobile.appserver.runtime.host

import okio.Path

internal data class RuntimeBundleDescriptor(
    val libraryVersion: String,
    val appServerVersion: String,
    val target: String,
    val classifier: String,
    val appServerName: String,
    val appServerSha256: String,
    val supervisorName: String,
)

internal data class InstalledRuntime(
    val appServer: Path,
    val supervisor: Path,
    val supervisorSha256: String,
)
