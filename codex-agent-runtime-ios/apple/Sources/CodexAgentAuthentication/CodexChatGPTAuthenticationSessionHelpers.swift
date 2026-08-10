import CodexAgent
import AuthenticationServices
import UIKit

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
            guard cancellationToken == nil, signOutToken == nil else { return }
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
        finish(attempt: attempt, error: message, cancelOperation: true)
        cancellationOperation?.cancel()
        let token = UUID()
        cancellationToken = token
        var completed = false
        let operation = driver.cancelAuthentication { [weak self] _ in
            completed = true
            guard self?.cancellationToken == token else { return }
            self?.cancellationToken = nil
            self?.cancellationOperation?.detach()
            self?.cancellationOperation = nil
        }
        if completed {
            cancellationToken = nil
            operation.detach()
        } else {
            cancellationOperation = operation
        }
    }

    func finish(
        attempt: UUID,
        error: String?,
        cancelOperation: Bool = false
    ) {
        guard activeAttempt?.id == attempt else { return }
        activeAttempt = nil
        isAuthenticating = false
        browserSession?.cancel()
        browserSession = nil
        if cancelOperation {
            authenticationOperation?.cancel()
        } else {
            authenticationOperation?.detach()
        }
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
