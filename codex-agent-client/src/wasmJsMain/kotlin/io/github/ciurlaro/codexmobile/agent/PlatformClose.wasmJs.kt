package io.github.ciurlaro.codexmobile.agent

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal actual fun CodexAgentClient.closeAction() {
    scope.launch(start = CoroutineStart.UNDISPATCHED) { closeSuspendingAction() }
}
