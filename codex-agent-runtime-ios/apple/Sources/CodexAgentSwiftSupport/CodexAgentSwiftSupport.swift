import CodexAgent
import Foundation

public extension CodexAgent {
    func authenticate() async throws {
        try await authenticate(method: CodexAuthenticationMethodChatGptBrowser())
    }

    func openConversation() async throws -> CodexConversation {
        try await openConversation(
            conversationId: nil,
            settings: AgentConversationSettings(
                approvalPreset: .autoReview,
                serviceTier: nil
            )
        )
    }
}

public extension CodexConversation {
    func send(_ prompt: String) async throws {
        try await send(prompt: prompt)
    }
}

public extension AgentElicitationResponse {
    static func decline() -> AgentElicitationResponse {
        companion.decline()
    }

    static func cancel() -> AgentElicitationResponse {
        companion.cancel()
    }
}

public extension Error {
    /** Structured Codex failure details, when this error came from a Codex operation. */
    var codexFailure: CodexFailure? {
        ((self as NSError).kotlinException as? CodexOperationException)?.failure
    }
}
