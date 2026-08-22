package io.github.codex_agent_labs.codexmobile.agent

import kotlinx.coroutines.runBlocking

// R062 disposable Android-only CI fixture; this branch is never merged.
internal actual fun CodexAgentClient.closeAction() = runBlocking { closeSuspendingAction() }
