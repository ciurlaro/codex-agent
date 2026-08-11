import CodexAgent
import AuthenticationServices
import UIKit

struct CodexAuthenticationCleanup: @unchecked Sendable {
    let driver: CodexAuthenticationDriving
    let cancelOwnedLogin: Bool
    let browserSession: CodexBrowserSession?
    let authenticationOperation: CodexOperationHandle?
    let auxiliaryOperations: [CodexOperationHandle?]
    let observations: [CodexCancellation?]

    @MainActor
    func perform() {
        if cancelOwnedLogin {
            var retainedOperation: CodexOperationHandle?
            var completed = false
            let operation = driver.cancelAuthentication { _ in
                completed = true
                retainedOperation?.detach()
                retainedOperation = nil
            }
            if completed {
                operation.detach()
            } else {
                retainedOperation = operation
            }
        }
        browserSession?.cancel()
        authenticationOperation?.detach()
        auxiliaryOperations.forEach { $0?.detach() }
        observations.forEach { $0?.close() }
    }
}

extension CodexChatGPTAuthenticationSession {
    func update(_ state: IosCodexAuthenticationState) {
        guard record(state) else { return }
        lastStateGeneration = state.generation
        isAuthenticated = state.status == .authenticated
        guard var attempt = activeAttempt else { return }
        if attempt.generation == nil {
            attempt.generation = state.generation
            activeAttempt = attempt
        }
        guard let attemptGeneration = attempt.generation else { return }
        switch state.status {
        case .authenticated:
            guard state.generation >= attemptGeneration else { return }
            finish(attempt: attempt.id, error: nil)
        case .signedOut:
            guard state.generation >= attemptGeneration else { return }
            guard pendingCancellation == nil, pendingSignOut == nil else { return }
            finish(
                attempt: attempt.id,
                error: state.terminalReason ?? "ChatGPT authentication was canceled or failed."
            )
        case .closed:
            guard state.generation >= attemptGeneration else { return }
            finish(
                attempt: attempt.id,
                error: state.terminalReason ?? "Codex Agent facade is closed."
            )
        case .authenticating:
            guard state.generation == attemptGeneration else {
                if state.generation > attemptGeneration && attempt.ownsLogin {
                    attempt.ownsLogin = false
                    activeAttempt = attempt
                }
                return
            }
            if let signInUrl = state.pendingSignInUrl {
                presentBrowser(signInUrl, attempt: attempt.id)
            }
        default:
            break
        }
    }

    func record(_ state: IosCodexAuthenticationState) -> Bool {
        guard state.generation >= lastStateGeneration else { return false }
        if state.generation == lastStateGeneration,
            state.status == lastStateStatus,
            state.pendingSignInUrl == lastPendingSignInUrl,
            state.terminalReason == lastTerminalReason {
            return false
        }
        lastStateStatus = state.status
        lastPendingSignInUrl = state.pendingSignInUrl
        lastTerminalReason = state.terminalReason
        return true
    }

    func receive(_ event: AgentEvent) {
        eventHandler?(event)
    }

    func presentBrowser(_ signInUrl: String, attempt: UUID) {
        guard browserSession == nil, activeAttempt?.id == attempt else { return }
        guard
            let url = URL(string: signInUrl),
            url.scheme?.lowercased() == "https",
            url.host != nil
        else {
            cancelFailedPresentation(attempt: attempt, message: "App Server returned an invalid ChatGPT sign-in URL.")
            return
        }
        guard let presentationAnchor = anchor ?? foregroundWindow() else {
            cancelFailedPresentation(attempt: attempt, message: "No foreground window is available for ChatGPT sign-in.")
            return
        }
        anchor = presentationAnchor

        let session = browserFactory(url) { [weak self] _, error in
            DispatchQueue.main.async {
                self?.browserDidComplete(attempt: attempt, error: error)
            }
        }
        (session as? ASWebAuthenticationSession)?.presentationContextProvider = self
        browserSession = session
        if !session.start() {
            browserSession = nil
            cancelFailedPresentation(attempt: attempt, message: "Could not present ChatGPT sign-in.")
        }
    }

    func browserDidComplete(attempt: UUID, error: Error?) {
        guard activeAttempt?.id == attempt else { return }
        browserSession = nil
        guard let error else { return }
        let nsError = error as NSError
        let message = nsError.domain == ASWebAuthenticationSessionErrorDomain &&
            nsError.code == ASWebAuthenticationSessionError.canceledLogin.rawValue
            ? "ChatGPT authentication was canceled."
            : error.localizedDescription
        cancelFailedPresentation(attempt: attempt, message: message)
    }

    func cancelFailedPresentation(attempt: UUID, message: String) {
        guard let activeAttempt, activeAttempt.id == attempt else { return }
        cancelAttempt(activeAttempt, message: message)
    }

    func cancelAttempt(
        _ attempt: Attempt,
        message: String,
        completion: ((String?) -> Void)? = nil
    ) {
        guard activeAttempt?.id == attempt.id else {
            completion?(nil)
            return
        }
        guard attempt.ownsLogin else {
            finish(attempt: attempt.id, error: message)
            completion?(nil)
            return
        }
        startCancellation(
            afterStart: { [self] in finish(attempt: attempt.id, error: message) },
            completion: completion
        )
    }

    func startCancellation(
        afterStart: () -> Void,
        completion: ((String?) -> Void)?
    ) {
        let token = UUID()
        pendingCancellation = AuxiliaryOperation(token: token)
        var returned = false
        var completedSynchronously = false
        var synchronousError: String?
        let operation = driver.cancelAuthentication { [weak self] error in
            guard let self, self.pendingCancellation?.token == token else { return }
            if !returned {
                completedSynchronously = true
                synchronousError = error
                return
            }
            self.completeCancellation(token: token, error: error, completion: completion)
        }
        if var pending = pendingCancellation, pending.token == token {
            pending.handle = operation
            pendingCancellation = pending
        } else {
            operation.detach()
        }
        afterStart()
        returned = true
        if completedSynchronously {
            completeCancellation(token: token, error: synchronousError, completion: completion)
        }
    }

    func completeCancellation(
        token: UUID,
        error: String?,
        completion: ((String?) -> Void)?
    ) {
        guard pendingCancellation?.token == token else { return }
        let operation = pendingCancellation?.handle
        pendingCancellation = nil
        operation?.detach()
        completion?(error)
    }

    func startSignOut(afterStart: () -> Void, completion: @escaping (String?) -> Void) {
        let token = UUID()
        pendingSignOut = AuxiliaryOperation(token: token)
        var returned = false
        var completedSynchronously = false
        var synchronousError: String?
        let operation = driver.signOut { [weak self] error in
            guard let self, self.pendingSignOut?.token == token else { return }
            if !returned {
                completedSynchronously = true
                synchronousError = error
                return
            }
            self.completeSignOut(token: token, error: error, completion: completion)
        }
        if var pending = pendingSignOut, pending.token == token {
            pending.handle = operation
            pendingSignOut = pending
        } else {
            operation.detach()
        }
        afterStart()
        returned = true
        if completedSynchronously {
            completeSignOut(token: token, error: synchronousError, completion: completion)
        }
    }

    func completeSignOut(
        token: UUID,
        error: String?,
        completion: @escaping (String?) -> Void
    ) {
        guard pendingSignOut?.token == token else { return }
        let operation = pendingSignOut?.handle
        pendingSignOut = nil
        operation?.detach()
        completion(error)
    }

    func finish(
        attempt: UUID,
        error: String?
    ) {
        guard activeAttempt?.id == attempt else { return }
        activeAttempt = nil
        isAuthenticating = false
        browserSession?.cancel()
        browserSession = nil
        authenticationOperation?.detach()
        authenticationOperation = nil
        let completion = self.completion
        self.completion = nil
        completion?(error)
    }

    func foregroundWindow() -> UIWindow? {
        let scenes = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }
        return scenes.lazy.compactMap { scene in
            scene.windows.first(where: \.isKeyWindow)
                ?? scene.windows.first(where: { !$0.isHidden })
        }.first
    }
}
