package io.github.codex_agent_labs.codexmobile.agent

import okio.FileSystem

internal actual val systemAgentFileStore: AgentFileStore = FileSystem.SYSTEM.asAgentFileStore()
