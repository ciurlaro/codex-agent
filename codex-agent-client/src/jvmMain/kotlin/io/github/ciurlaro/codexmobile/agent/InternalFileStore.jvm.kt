package io.github.ciurlaro.codexmobile.agent

import okio.FileSystem

internal actual val systemAgentFileStore: AgentFileStore = FileSystem.SYSTEM.asAgentFileStore()
