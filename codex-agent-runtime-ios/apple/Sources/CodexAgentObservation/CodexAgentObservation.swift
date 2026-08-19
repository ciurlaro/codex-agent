import CodexAgent

public extension CodexHost {
    var states: AsyncStream<any CodexHostState> {
        codexStateStream(state) { $0 as? any CodexHostState }
    }
}

public extension CodexAgent {
    var authenticationStates: AsyncStream<AgentAuthenticationState> {
        codexStateStream(authenticationState) { $0 as? AgentAuthenticationState }
    }

    var interactionStates: AsyncStream<AgentInteractionState> {
        codexStateStream(interactionState) { $0 as? AgentInteractionState }
    }

    var integrationAuthorizationStates: AsyncStream<AgentIntegrationAuthorizationState> {
        codexStateStream(integrationAuthorizationState) { $0 as? AgentIntegrationAuthorizationState }
    }

    var activeConversations: AsyncStream<CodexConversation?> {
        codexOptionalStateStream(activeConversation, as: CodexConversation.self)
    }
}

public extension CodexConversation {
    var states: AsyncStream<AgentConversationState> {
        codexStateStream(state) { $0 as? AgentConversationState }
    }
}

func codexAsyncStream<Element>(
    observe: (@escaping (Element) -> Void) -> () -> Void
) -> AsyncStream<Element> {
    AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
        let close = observe { continuation.yield($0) }
        continuation.onTermination = { _ in close() }
    }
}

private func codexStateStream<Element>(
    _ state: any Kotlinx_coroutines_coreStateFlow,
    cast: @escaping (Any?) -> Element?
) -> AsyncStream<Element> {
    codexAsyncStream { yield in
        let observation = CodexStateObservation(state: state) { value in
            if let value = cast(value) { yield(value) }
        }
        return observation.close
    }
}

private func codexOptionalStateStream<Element: AnyObject>(
    _ state: any Kotlinx_coroutines_coreStateFlow,
    as _: Element.Type
) -> AsyncStream<Element?> {
    codexAsyncStream { yield in
        let observation = CodexStateObservation(state: state) { value in
            guard value == nil || value is Element else { return }
            yield(value as? Element)
        }
        return observation.close
    }
}
