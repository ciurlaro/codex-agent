package io.github.ciurlaro.codexmobile.agent

import kotlinx.coroutines.runBlocking

internal actual fun CodexAgentClient.closeAction() = runBlocking { closeSuspendingAction() }
