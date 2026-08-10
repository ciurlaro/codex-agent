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
    var cancellationOperation: CodexOperationHandle?
    var cancellationToken: UUID?
    var signOutOperation: CodexOperationHandle?
    var signOutToken: UUID?
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
        let supersedesAuxiliaryOperation = cancellationToken != nil || signOutToken != nil
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
        guard cancellationOperation == nil else {
            completion?(nil)
            return
        }
        let state = driver.authenticationState
        guard activeAttempt != nil || state.status == .authenticating else {
            completion?(nil)
            return
        }
        if let attempt = activeAttempt {
            finish(
                attempt: attempt.id,
                error: "ChatGPT authentication was canceled.",
                cancelOperation: true
            )
        }
        let token = UUID()
        cancellationToken = token
        var completed = false
        let operation = driver.cancelAuthentication { [weak self] error in
            completed = true
            guard self?.cancellationToken == token else { return }
            self?.cancellationToken = nil
            self?.cancellationOperation?.detach()
            self?.cancellationOperation = nil
            completion?(error)
        }
        if completed {
            cancellationToken = nil
            operation.detach()
        } else {
            cancellationOperation = operation
        }
    }

    public func signOut(completion: @escaping (String?) -> Void) {
        guard !closed else {
            completion("ChatGPT authentication session is closed.")
            return
        }
        if let attempt = activeAttempt {
            finish(
                attempt: attempt.id,
                error: "ChatGPT authentication was canceled by sign-out.",
                cancelOperation: true
            )
        }
        cancellationOperation?.cancel()
        cancellationOperation = nil
        cancellationToken = nil
        signOutOperation?.cancel()
        let token = UUID()
        signOutToken = token
        var completed = false
        let operation = driver.signOut { [weak self] error in
            completed = true
            guard self?.signOutToken == token else { return }
            self?.signOutToken = nil
            self?.signOutOperation?.detach()
            self?.signOutOperation = nil
            completion(error)
        }
        if completed {
            signOutToken = nil
            operation.detach()
        } else {
            signOutOperation = operation
        }
    }

    public func close() {
        cleanup()
    }

    deinit {
        let driver = driver
        let ownsLogin = activeAttempt?.ownsLogin == true
        let noCancellation = cancellationToken == nil
        let noSignOut = signOutToken == nil
        let shouldCancelLogin = ownsLogin && noCancellation && noSignOut
        let browserSession = browserSession
        let authenticationOperation = authenticationOperation
        let cancellationOperation = cancellationOperation
        let signOutOperation = signOutOperation
        let eventObservation = eventObservation
        let stateObservation = stateObservation
        DispatchQueue.main.async {
            browserSession?.cancel()
            if shouldCancelLogin {
                authenticationOperation?.cancel()
            } else {
                authenticationOperation?.detach()
            }
            cancellationOperation?.detach()
            signOutOperation?.detach()
            eventObservation?.close()
            stateObservation?.close()
            guard shouldCancelLogin else { return }
            var cleanupOperation: CodexOperationHandle?
            cleanupOperation = driver.cancelAuthentication { _ in
                cleanupOperation?.detach()
                cleanupOperation = nil
            }
        }
    }

    private func cleanup() {
        guard !closed else { return }
        let shouldCancelLogin = activeAttempt?.ownsLogin == true &&
            cancellationToken == nil && signOutToken == nil
        closed = true
        if shouldCancelLogin {
            var cleanupOperation: CodexOperationHandle?
            var completed = false
            let operation = driver.cancelAuthentication { _ in
                completed = true
                cleanupOperation?.detach()
                cleanupOperation = nil
            }
            if completed {
                operation.detach()
            } else {
                cleanupOperation = operation
            }
        }
        if let attempt = activeAttempt {
            finish(
                attempt: attempt.id,
                error: "ChatGPT authentication session was closed.",
                cancelOperation: attempt.ownsLogin
            )
        }
        browserSession?.cancel()
        browserSession = nil
        if shouldCancelLogin {
            authenticationOperation?.cancel()
        } else {
            authenticationOperation?.detach()
        }
        authenticationOperation = nil
        cancellationOperation?.detach()
        cancellationOperation = nil
        cancellationToken = nil
        signOutOperation?.detach()
        signOutOperation = nil
        signOutToken = nil
        eventObservation?.close()
        eventObservation = nil
        stateObservation?.close()
        stateObservation = nil
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
