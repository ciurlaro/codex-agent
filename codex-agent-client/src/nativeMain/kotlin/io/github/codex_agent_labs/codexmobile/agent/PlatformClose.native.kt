package io.github.codex_agent_labs.codexmobile.agent

import kotlinx.coroutines.runBlocking

internal actual fun CodexAgentClient.closeAction() = runBlocking { closeSuspendingAction() }
