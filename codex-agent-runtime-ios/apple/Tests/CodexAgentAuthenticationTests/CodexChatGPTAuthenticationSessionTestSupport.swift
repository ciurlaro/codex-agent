import CodexAgent
import Foundation
@testable import CodexAgentAuthentication

@MainActor
extension CodexChatGPTAuthenticationSessionTests {
    func makeSession(
        _ driver: FakeAuthenticationDriver,
        _ suppliedBrowsers: BrowserStore? = nil
    ) -> CodexChatGPTAuthenticationSession {
        let browsers = suppliedBrowsers ?? BrowserStore()
        return CodexChatGPTAuthenticationSession(
            driver: driver,
            browserFactory: { _, completion in
                let session = FakeBrowserSession(completion: completion)
                browsers.sessions.append(session)
                return session
            }
        )
    }
}

@MainActor
final class FakeAuthenticationDriver: CodexAuthenticationDriving {
    private(set) var authenticationState: IosCodexAuthenticationState
    private var eventObservers: [UUID: (AgentEvent) -> Void] = [:]
    private var stateObservers: [UUID: (IosCodexAuthenticationState) -> Void] = [:]
    private var generation: Int64 = 0
    private var pendingCancellations: [(Int64, (String?) -> Void)] = []
    private var pendingSignOuts: [(Int64, (String?) -> Void)] = []
    private let autoCompleteAuthenticationOperation: Bool
    private let autoCompleteAuxiliaryOperations: Bool
    private(set) var authenticationCalls = 0
    private(set) var cancellationCalls = 0
    private(set) var signOutCalls = 0
    private(set) var authenticationOperationCancellations = 0
    private(set) var authenticationOperationDetachments = 0
    private(set) var auxiliaryOperationCancellations = 0
    private(set) var auxiliaryOperationDetachments = 0
    private(set) var appServerLoginCancellationCalls = 0
    private(set) var hasActiveLogin = false

    var eventObserverCount: Int { eventObservers.count }
    var stateObserverCount: Int { stateObservers.count }

    init(
        status: IosCodexAuthenticationStatus = .signedOut,
        autoCompleteAuthenticationOperation: Bool = true,
        autoCompleteAuxiliaryOperations: Bool = true
    ) {
        authenticationState = IosCodexAuthenticationState(
            status: status,
            generation: 0,
            pendingSignInUrl: nil,
            terminalReason: nil
        )
        self.autoCompleteAuthenticationOperation = autoCompleteAuthenticationOperation
        self.autoCompleteAuxiliaryOperations = autoCompleteAuxiliaryOperations
    }

    func observeEvents(_ observer: @escaping (AgentEvent) -> Void) -> CodexCancellation {
        let id = UUID()
        eventObservers[id] = observer
        return CodexCancellation { [weak self] in self?.eventObservers[id] = nil }
    }

    func observeAuthenticationState(
        _ observer: @escaping (IosCodexAuthenticationState) -> Void
    ) -> CodexCancellation {
        let id = UUID()
        stateObservers[id] = observer
        observer(authenticationState)
        return CodexCancellation { [weak self] in self?.stateObservers[id] = nil }
    }

    func authenticate(_ completion: @escaping (String?) -> Void) -> CodexOperationHandle {
        generation += 1
        let operationGeneration = generation
        authenticationCalls += 1
        hasActiveLogin = true
        setState(.authenticating, generation: operationGeneration)
        if autoCompleteAuthenticationOperation {
            completion(nil)
        }
        return CodexOperationHandle(
            generation: operationGeneration,
            cancel: { [weak self] in
                guard let self else { return }
                self.authenticationOperationCancellations += 1
                guard self.generation == operationGeneration,
                    self.authenticationState.status == .authenticating else { return }
                self.appServerLoginCancellationCalls += 1
                self.hasActiveLogin = false
                self.setState(.signedOut, generation: operationGeneration)
            },
            detach: { [weak self] in self?.authenticationOperationDetachments += 1 }
        )
    }

    func cancelAuthentication(_ completion: @escaping (String?) -> Void) -> CodexOperationHandle {
        generation += 1
        cancellationCalls += 1
        appServerLoginCancellationCalls += 1
        let pending = (generation, completion)
        if autoCompleteAuxiliaryOperations {
            complete(
                pending,
                status: .signedOut,
                terminalReason: "ChatGPT authentication was canceled."
            )
        } else {
            pendingCancellations.append(pending)
        }
        return auxiliaryOperation(generation: generation)
    }

    func signOut(_ completion: @escaping (String?) -> Void) -> CodexOperationHandle {
        generation += 1
        signOutCalls += 1
        let pending = (generation, completion)
        if autoCompleteAuxiliaryOperations {
            complete(
                pending,
                status: .signedOut,
                terminalReason: "ChatGPT authentication was canceled by sign-out."
            )
        } else {
            pendingSignOuts.append(pending)
        }
        return auxiliaryOperation(generation: generation)
    }

    func completeCancellation() {
        guard !pendingCancellations.isEmpty else { return }
        complete(
            pendingCancellations.removeFirst(),
            status: .signedOut,
            terminalReason: "ChatGPT authentication was canceled."
        )
    }

    func completeSignOut() {
        guard !pendingSignOuts.isEmpty else { return }
        complete(
            pendingSignOuts.removeFirst(),
            status: .signedOut,
            terminalReason: "ChatGPT authentication was canceled by sign-out."
        )
    }

    func requireBrowser(_ url: String) {
        let event = AgentEventAuthenticationRequired(signInUrl: url)
        eventObservers.values.forEach { $0(event) }
        authenticationState = IosCodexAuthenticationState(
            status: .authenticating,
            generation: generation,
            pendingSignInUrl: url,
            terminalReason: nil
        )
        notifyState()
    }

    func beginObserverOwnedLogin() {
        generation += 1
        hasActiveLogin = true
        setState(.authenticating, generation: generation)
    }

    func succeed() {
        hasActiveLogin = false
        eventObservers.values.forEach { $0(AgentEventAuthenticated.shared) }
        setState(.authenticated, generation: generation)
    }

    func fail(_ message: String, code: String = "authentication_failed") {
        hasActiveLogin = false
        let event = AgentEventFailure(
            sessionId: nil,
            code: code,
            message: message,
            recoverable: true
        )
        eventObservers.values.forEach { $0(event) }
        setState(.signedOut, generation: generation, terminalReason: message)
    }

    func closeFacade() {
        generation += 1
        setState(
            .closed,
            generation: generation,
            terminalReason: "Codex Agent facade is closed."
        )
    }

    func emitState(
        generation: Int64,
        status: IosCodexAuthenticationStatus,
        terminalReason: String? = nil
    ) {
        setState(status, generation: generation, terminalReason: terminalReason)
    }

    private func complete(
        _ pending: (Int64, (String?) -> Void),
        status: IosCodexAuthenticationStatus,
        terminalReason: String?
    ) {
        if pending.0 == generation {
            hasActiveLogin = false
            setState(status, generation: pending.0, terminalReason: terminalReason)
        }
        pending.1(nil)
    }

    private func setState(
        _ status: IosCodexAuthenticationStatus,
        generation: Int64,
        terminalReason: String? = nil
    ) {
        authenticationState = IosCodexAuthenticationState(
            status: status,
            generation: generation,
            pendingSignInUrl: nil,
            terminalReason: terminalReason
        )
        notifyState()
    }

    private func auxiliaryOperation(generation: Int64) -> CodexOperationHandle {
        CodexOperationHandle(
            generation: generation,
            cancel: { [weak self] in self?.auxiliaryOperationCancellations += 1 },
            detach: { [weak self] in self?.auxiliaryOperationDetachments += 1 }
        )
    }

    private func notifyState() {
        stateObservers.values.forEach { $0(authenticationState) }
    }
}

@MainActor
final class BrowserStore {
    var sessions: [FakeBrowserSession] = []
}

@MainActor
final class FakeBrowserSession: CodexBrowserSession {
    private let completion: (URL?, Error?) -> Void
    private(set) var cancellationCount = 0

    init(completion: @escaping (URL?, Error?) -> Void) {
        self.completion = completion
    }

    func start() -> Bool { true }
    func cancel() { cancellationCount += 1 }
    func complete(_ error: Error?) { completion(nil, error) }
}
