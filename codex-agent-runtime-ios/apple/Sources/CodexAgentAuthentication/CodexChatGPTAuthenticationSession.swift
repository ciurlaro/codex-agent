import CodexAgent
import AuthenticationServices
import UIKit

@MainActor
public final class CodexChatGPTAuthenticationSession: NSObject,
    ASWebAuthenticationPresentationContextProviding {
    public var eventHandler: ((AgentEvent) -> Void)?
    public internal(set) var isAuthenticating = false
    public internal(set) var isAuthenticated = false

    let driver: CodexAuthenticationDriving
    let browserFactory: CodexBrowserSessionFactory
    var eventObservation: CodexCancellation?
    var stateObservation: CodexCancellation?
    var authenticationOperation: CodexOperationHandle?
    struct AuxiliaryOperation {
        let token: UUID
        var handle: CodexOperationHandle?
    }

    var pendingCancellation: AuxiliaryOperation?
    var pendingSignOut: AuxiliaryOperation?
    var browserSession: CodexBrowserSession?
    weak var anchor: ASPresentationAnchor?
    struct Attempt {
        let id: UUID
        var generation: Int64?
        var ownsLogin: Bool
    }

    var activeAttempt: Attempt?
    var completion: ((String?) -> Void)?
    var lastStateGeneration: Int64 = 0
    var lastStateStatus: IosCodexAuthenticationStatus?
    var lastPendingSignInUrl: String?
    var lastTerminalReason: String?
    var closed = false

    public convenience init(facade: IosCodexAgentFacade) {
        self.init(
            driver: IosFacadeAuthenticationDriver(facade),
            browserFactory: { url, completion in
                ASWebAuthenticationSession(
                    url: url,
                    callbackURLScheme: nil,
                    completionHandler: completion
                )
            }
        )
    }

    init(
        driver: CodexAuthenticationDriving,
        browserFactory: @escaping CodexBrowserSessionFactory
    ) {
        self.driver = driver
        self.browserFactory = browserFactory
        super.init()
        lastStateGeneration = driver.authenticationState.generation
        update(driver.authenticationState)
        eventObservation = driver.observeEvents { [weak self] event in
            self?.receive(event)
        }
        stateObservation = driver.observeAuthenticationState { [weak self] state in
            self?.update(state)
        }
    }

    public func authenticate(
        from presentationAnchor: ASPresentationAnchor? = nil,
        completion: @escaping (String?) -> Void
    ) {
        guard !closed else {
            completion("ChatGPT authentication session is closed.")
            return
        }
        guard activeAttempt == nil else {
            completion("ChatGPT authentication is already in progress.")
            return
        }

        let state = driver.authenticationState
        let supersedesAuxiliaryOperation = pendingCancellation != nil || pendingSignOut != nil
        if state.status == .authenticated && !supersedesAuxiliaryOperation {
            update(state)
            completion(nil)
            return
        }
        guard state.status != .closed else {
            completion("Codex Agent facade is closed.")
            return
        }

        let attemptID = UUID()
        let ownsLogin = state.status != .authenticating || supersedesAuxiliaryOperation
        activeAttempt = Attempt(
            id: attemptID,
            generation: ownsLogin ? nil : state.generation,
            ownsLogin: ownsLogin
        )
        anchor = presentationAnchor
        self.completion = completion
        isAuthenticating = true

        if state.status == .authenticating && !supersedesAuxiliaryOperation {
            if let signInUrl = state.pendingSignInUrl {
                presentBrowser(signInUrl, attempt: attemptID)
            }
            return
        }

        var completedSynchronously = false
        let operation = driver.authenticate { [weak self] error in
            completedSynchronously = true
            guard let self, self.activeAttempt?.id == attemptID else { return }
            if let error {
                self.finish(attempt: attemptID, error: error)
            } else {
                self.authenticationOperation?.detach()
                self.authenticationOperation = nil
            }
        }
        guard activeAttempt?.id == attemptID else {
            operation.detach()
            return
        }
        activeAttempt?.generation = operation.generation
        if completedSynchronously {
            operation.detach()
        } else {
            authenticationOperation = operation
        }
    }

    public func cancel(completion: ((String?) -> Void)? = nil) {
        guard !closed else {
            completion?(nil)
            return
        }
        guard pendingCancellation == nil else {
            completion?(nil)
            return
        }
        guard let attempt = activeAttempt else {
            completion?(nil)
            return
        }
        cancelAttempt(
            attempt,
            message: "ChatGPT authentication was canceled.",
            completion: completion
        )
    }

    public func signOut(completion: @escaping (String?) -> Void) {
        guard !closed else {
            completion("ChatGPT authentication session is closed.")
            return
        }
        pendingCancellation?.handle?.cancel()
        pendingCancellation = nil
        pendingSignOut?.handle?.cancel()
        pendingSignOut = nil
        startSignOut(
            afterStart: { [self] in
                guard let attempt = activeAttempt else { return }
                finish(
                    attempt: attempt.id,
                    error: "ChatGPT authentication was canceled by sign-out."
                )
            },
            completion: completion
        )
    }

    public func close() {
        cleanup()
    }

    deinit {
        guard !closed else { return }
        let ownsLogin = activeAttempt?.ownsLogin == true
        let hasCancellation = pendingCancellation != nil
        let hasSignOut = pendingSignOut != nil
        let cleanup = CodexAuthenticationCleanup(
            driver: driver,
            cancelOwnedLogin: ownsLogin && !hasCancellation && !hasSignOut,
            browserSession: browserSession,
            authenticationOperation: authenticationOperation,
            auxiliaryOperations: [pendingCancellation?.handle, pendingSignOut?.handle],
            observations: [eventObservation, stateObservation]
        )
        DispatchQueue.main.async {
            cleanup.perform()
        }
    }

    private func cleanup() {
        guard !closed else { return }
        closed = true
        let cancelOwnedLogin = activeAttempt?.ownsLogin == true &&
            pendingCancellation == nil && pendingSignOut == nil
        activeAttempt = nil
        isAuthenticating = false
        let attemptCompletion = completion
        completion = nil
        let resources = CodexAuthenticationCleanup(
            driver: driver,
            cancelOwnedLogin: cancelOwnedLogin,
            browserSession: browserSession,
            authenticationOperation: authenticationOperation,
            auxiliaryOperations: [pendingCancellation?.handle, pendingSignOut?.handle],
            observations: [eventObservation, stateObservation]
        )
        browserSession = nil
        authenticationOperation = nil
        pendingCancellation = nil
        pendingSignOut = nil
        eventObservation = nil
        stateObservation = nil
        resources.perform()
        attemptCompletion?("ChatGPT authentication session was closed.")
    }

    public func presentationAnchor(
        for session: ASWebAuthenticationSession
    ) -> ASPresentationAnchor {
        guard let anchor else {
            preconditionFailure("A foreground presentation window is required")
        }
        return anchor
    }
}
