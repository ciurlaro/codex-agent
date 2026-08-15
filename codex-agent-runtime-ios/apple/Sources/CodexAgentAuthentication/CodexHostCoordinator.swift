import CodexAgent
import Foundation

public struct CodexHostOperationError: LocalizedError, Sendable {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var errorDescription: String? { message }
}

@MainActor
public final class CodexHostCoordinator {
    private let facade: IosCodexHostFacade

    public init(
        sandboxRootPath: String,
        credentialProtection: IosCodexCredentialProtection = .whenUnlocked,
        clientVersion: String,
        browser: (any CodexAuthorizationBrowser)? = nil
    ) {
        facade = IosCodexHostFacade(
            sandboxRootPath: sandboxRootPath,
            credentialProtection: credentialProtection,
            clientVersion: clientVersion,
            browser: browser ?? CodexWebAuthenticationBrowser()
        )
    }

    public var state: IosCodexHostSnapshot { facade.currentState }

    public var states: AsyncStream<IosCodexHostSnapshot> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let observation = CodexHostObservationBox(
                facade.observeState { snapshot in
                    DispatchQueue.main.async { continuation.yield(snapshot) }
                }
            )
            continuation.onTermination = { _ in observation.close() }
        }
    }

    public func start() async throws {
        try await perform(facade.start)
    }

    public func selectWorkspace(_ url: URL) async throws {
        try await perform { completion in
            self.facade.selectWorkspace(url: url, completion: completion)
        }
    }

    public func retry() async throws {
        try await perform(facade.retry)
    }

    public func openConversation(
        previousSessionId: String? = nil,
        settings: AgentRuntimeSettings = AgentRuntimeSettings(
            approvalPreset: .autoReview,
            serviceTier: nil,
            workingDirectory: nil
        )
    ) async throws {
        try await perform { completion in
            self.facade.openConversation(
                previousSessionId: previousSessionId,
                settings: settings,
                completion: completion
            )
        }
    }

    public func closeConversation() async throws {
        try await perform(facade.closeConversation)
    }

    public func authenticateWithChatGPT() async throws {
        try await perform(facade.authenticateWithChatGpt)
    }

    public func authenticate(apiKey: String) async throws {
        try await perform { completion in
            self.facade.authenticateWithApiKey(apiKey: apiKey, completion: completion)
        }
    }

    public func cancelAuthentication() async throws {
        try await perform(facade.cancelAuthentication)
    }

    public func signOut() async throws {
        try await perform(facade.signOut)
    }

    public func resolveApproval(
        requestId: String,
        decision: AgentApprovalDecision
    ) async throws {
        try await perform { completion in
            self.facade.resolveApproval(
                requestId: requestId,
                decision: decision,
                completion: completion
            )
        }
    }

    public func resolveElicitation(
        requestId: String,
        response: AgentElicitationResponse
    ) async throws {
        try await perform { completion in
            self.facade.resolveElicitation(
                requestId: requestId,
                response: response,
                completion: completion
            )
        }
    }

    public func openElicitationURL(requestId: String) async throws {
        try await perform { completion in
            self.facade.openElicitationUrl(requestId: requestId, completion: completion)
        }
    }

    public func startMCPAuthorization(
        serverName: String,
        sessionId: String? = nil
    ) async throws {
        try await perform { completion in
            self.facade.startMcpAuthorization(
                serverName: serverName,
                sessionId: sessionId,
                completion: completion
            )
        }
    }

    public func dismissMCPBrowser() async throws {
        try await perform(facade.dismissMcpBrowser)
    }

    public func send(_ request: AgentTurnRequest) async throws {
        try await perform { completion in
            self.facade.sendTurn(request: request, completion: completion)
        }
    }

    public func runShellCommand(_ command: String) async throws {
        try await perform { completion in
            self.facade.runShellCommand(command: command, completion: completion)
        }
    }

    public func cancelTurn() async throws {
        try await perform(facade.cancelTurn)
    }

    public func refreshConversation() async throws {
        try await perform(facade.refreshConversation)
    }

    public func close() {
        facade.close()
    }

    private func perform(
        _ start: @escaping (@escaping (String?) -> Void) -> IosCodexOperation
    ) async throws {
        let operation = CodexHostAsyncOperation()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                guard operation.prepare(continuation) else { return }
                operation.install(
                    start { error in
                        DispatchQueue.main.async { operation.finish(error) }
                    }
                )
            }
        } onCancel: {
            Task { @MainActor in operation.cancel() }
        }
    }
}

private final class CodexHostObservationBox: @unchecked Sendable {
    private let observation: IosCodexObservation

    init(_ observation: IosCodexObservation) {
        self.observation = observation
    }

    func close() {
        observation.close()
    }
}

@MainActor
private final class CodexHostAsyncOperation {
    private var continuation: CheckedContinuation<Void, Error>?
    private var operation: IosCodexOperation?
    private var completed = false
    private var cancelled = false

    func prepare(_ continuation: CheckedContinuation<Void, Error>) -> Bool {
        guard !completed else {
            continuation.resume(throwing: CancellationError())
            return false
        }
        self.continuation = continuation
        return true
    }

    func install(_ operation: IosCodexOperation) {
        guard !completed else {
            if cancelled { operation.cancel() } else { operation.close() }
            return
        }
        self.operation = operation
    }

    func finish(_ error: String?) {
        guard !completed else { return }
        completed = true
        operation?.close()
        operation = nil
        let continuation = self.continuation
        self.continuation = nil
        if let error {
            continuation?.resume(throwing: CodexHostOperationError(error))
        } else {
            continuation?.resume()
        }
    }

    func cancel() {
        guard !completed else { return }
        completed = true
        cancelled = true
        operation?.cancel()
        operation = nil
        let continuation = self.continuation
        self.continuation = nil
        continuation?.resume(throwing: CancellationError())
    }
}
