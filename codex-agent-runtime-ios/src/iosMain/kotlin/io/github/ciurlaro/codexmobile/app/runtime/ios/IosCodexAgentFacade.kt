package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.CodexAgentClient
import io.github.ciurlaro.codexmobile.agent.CodexAuthenticationMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class IosCodexAgentFacade(
    configuration: IosCodexRuntimeConfiguration,
    clientVersion: String,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtimeFactory = IosCodexRuntimeFactory(configuration)

    val client = CodexAgentClient(
        runtimeFactory = runtimeFactory,
        clientVersion = clientVersion,
        builtInToolDispatcher = runtimeFactory.workspaceTools,
    )

    fun observeEvents(observer: (AgentEvent) -> Unit): IosCodexObservation =
        IosCodexObservation(scope.launch { client.events.collect(observer) })

    fun authenticateWithApiKey(
        apiKey: String,
        completion: (String?) -> Unit,
    ): IosCodexOperation = launchOperation(completion) {
        client.authenticate(CodexAuthenticationMethod.ApiKey(apiKey))
    }

    fun authenticateWithDeviceCode(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) {
            client.authenticate(CodexAuthenticationMethod.ChatGptDeviceCode)
        }

    fun cancelAuthentication(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) { client.cancelAuthentication() }

    private fun launchOperation(
        completion: (String?) -> Unit,
        operation: suspend () -> Unit,
    ) = IosCodexOperation(
        scope.launch {
            completion(runCatching { operation() }.exceptionOrNull()?.message)
        },
    )

    override fun close() {
        client.close()
        scope.cancel()
    }
}

class IosCodexObservation internal constructor(
    private val job: Job,
) : AutoCloseable {
    override fun close() = job.cancel()
}

class IosCodexOperation internal constructor(
    private val job: Job,
) : AutoCloseable {
    override fun close() = job.cancel()
}
