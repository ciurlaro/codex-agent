import CodexAgent
@testable import CodexAgentObservation
import CodexAgentSwiftSupport
import XCTest

final class CodexAgentObservationTests: XCTestCase {
    func testBufferingCancellationAndDroppedStreamReleaseTheObservation() async {
        let buffered: AsyncStream<Int> = codexAsyncStream { yield in
            yield(1)
            yield(2)
            return {}
        }
        var iterator = buffered.makeAsyncIterator()
        let latest = await iterator.next()
        XCTAssertEqual(latest, 2)

        let closed = expectation(description: "observation closed")
        let cancellable: AsyncStream<Int> = codexAsyncStream { _ in
            return { closed.fulfill() }
        }
        let task = Task {
            for await _ in cancellable { /* wait for cancellation */ }
        }
        task.cancel()
        await fulfillment(of: [closed], timeout: 1)

        let droppedClosed = expectation(description: "dropped observation closed")
        let droppedDeinitialized = expectation(description: "dropped observation released")
        weak var weakToken: TestObservationToken?
        var dropped: AsyncStream<Int>? = codexAsyncStream { yield in
            let token = TestObservationToken(
                onClose: { droppedClosed.fulfill() },
                onDeinit: { droppedDeinitialized.fulfill() }
            )
            token.yield = yield
            weakToken = token
            return token.close
        }
        XCTAssertNotNil(dropped)
        XCTAssertNotNil(weakToken)

        dropped = nil

        await fulfillment(of: [droppedClosed, droppedDeinitialized], timeout: 1)
        XCTAssertNil(weakToken)
    }

    func testCodexOperationErrorsExposeStructuredFailure() {
        let failure = CodexFailure(
            code: "workspace_unavailable",
            message: "Workspace is unavailable",
            isRecoverable: true
        )
        let error = CodexOperationException(failure: failure, cause: nil).asError()

        XCTAssertEqual(error.codexFailure?.code, failure.code)
        XCTAssertEqual(error.codexFailure?.message, failure.message)
        XCTAssertEqual(error.codexFailure?.isRecoverable, failure.isRecoverable)
    }
}

private final class TestObservationToken {
    var yield: ((Int) -> Void)?
    private let onClose: () -> Void
    private let onDeinit: () -> Void
    private var isClosed = false

    init(onClose: @escaping () -> Void, onDeinit: @escaping () -> Void) {
        self.onClose = onClose
        self.onDeinit = onDeinit
    }

    func close() {
        guard !isClosed else { return }
        isClosed = true
        yield = nil
        onClose()
    }

    deinit { onDeinit() }
}

private func compileTypedObservationSurface(
    host: CodexHost,
    agent: CodexAgent,
    conversation: CodexConversation
) {
    let _: AsyncStream<any CodexHostState> = host.states
    let _: AsyncStream<AgentAuthenticationState> = agent.authenticationStates
    let _: AsyncStream<AgentInteractionState> = agent.interactionStates
    let _: AsyncStream<AgentIntegrationAuthorizationState> = agent.integrationAuthorizationStates
    let _: AsyncStream<CodexConversation?> = agent.activeConversations
    let _: AsyncStream<AgentConversationState> = conversation.states
}

private func compileSimpleAndAdvancedOperations(
    agent: CodexAgent,
    conversation: CodexConversation
) async throws {
    try await agent.authenticate()
    try await agent.authenticate(method: CodexAuthenticationMethodChatGptDeviceCode())
    _ = try await agent.openConversation()
    _ = try await agent.openConversation(
        conversationId: nil,
        settings: AgentConversationSettings(
            approvalPreset: .strict,
            serviceTier: nil
        )
    )
    try await conversation.send("Hello")
    try await conversation.send(prompt: "Hello")
}

private func compileCapabilitiesMutationsAndElicitation(
    agent: CodexAgent,
    conversationState: AgentConversationState,
    skill: AgentSkill,
    hook: AgentHook,
    pluginReference: AgentPluginReference,
    plugin: AgentPluginSummary,
    elicitation: AgentElicitation
) async throws {
    let _: Set<CodexRuntimeFeature> = agent.features
    let _: AgentConversationStatus = .closed
    _ = conversationState.canStartTurn
    _ = conversationState.canReload
    _ = conversationState.canCancelTurn

    try await agent.setSkillEnabled(skill: skill, isEnabled: true)
    try await agent.setHookEnabled(hook: hook, isEnabled: true)
    try await agent.trustHook(hook: hook)
    _ = try await agent.installPlugin(plugin: pluginReference)
    try await agent.uninstallPlugin(plugin: pluginReference)
    try await agent.setPluginEnabled(plugin: plugin, isEnabled: true)

    let initial = elicitation.initialValues()
    let validation = elicitation.validate(content: initial)
    _ = validation.isValid
    let accepted = elicitation.accept(content: initial)
    _ = elicitation.accepts(response: accepted)
    _ = AgentElicitationResponse.decline()
    _ = AgentElicitationResponse.cancel()
}
