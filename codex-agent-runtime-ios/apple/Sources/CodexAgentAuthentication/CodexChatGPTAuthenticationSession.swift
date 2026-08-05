@_exported import CodexAgent
import AuthenticationServices
import UIKit

@MainActor
public final class CodexChatGPTAuthenticationSession: NSObject,
    ASWebAuthenticationPresentationContextProviding {
    public var eventHandler: ((AgentEvent) -> Void)?
    public private(set) var isAuthenticating = false
    public private(set) var isAuthenticated = false

    private let facade: IosCodexAgentFacade
    private var observation: IosCodexObservation?
    private var authenticationOperation: IosCodexOperation?
    private var cancellationOperation: IosCodexOperation?
    private var browserSession: ASWebAuthenticationSession?
    private weak var anchor: ASPresentationAnchor?
    private var activeAttempt: UUID?
    private var completion: ((String?) -> Void)?

    public init(facade: IosCodexAgentFacade) {
        self.facade = facade
        super.init()
        observation = facade.observeEvents { [weak self] event in
            DispatchQueue.main.async {
                self?.receive(event)
            }
        }
    }

    public func authenticate(
        from presentationAnchor: ASPresentationAnchor? = nil,
        completion: @escaping (String?) -> Void
    ) {
        guard !isAuthenticated else {
            completion(nil)
            return
        }
        guard activeAttempt == nil else {
            completion("ChatGPT authentication is already in progress.")
            return
        }

        let attempt = UUID()
        activeAttempt = attempt
        anchor = presentationAnchor
        self.completion = completion
        isAuthenticating = true
        authenticationOperation = facade.authenticateWithChatGpt { [weak self] error in
            guard let error else { return }
            DispatchQueue.main.async {
                self?.finish(attempt: attempt, error: error)
            }
        }
    }

    public func cancel(completion: ((String?) -> Void)? = nil) {
        guard let attempt = activeAttempt else {
            completion?(nil)
            return
        }
        cancelLogin(
            attempt: attempt,
            message: "ChatGPT authentication was canceled.",
            cancellationCompletion: completion
        )
    }

    public func close() {
        if let attempt = activeAttempt {
            cancelLogin(attempt: attempt, message: "ChatGPT authentication was canceled.")
        }
        observation?.close()
        observation = nil
    }

    public func presentationAnchor(
        for session: ASWebAuthenticationSession
    ) -> ASPresentationAnchor {
        guard let anchor else {
            preconditionFailure("A foreground presentation window is required")
        }
        return anchor
    }

    private func receive(_ event: AgentEvent) {
        eventHandler?(event)
        let authenticated = event is AgentEventAuthenticated
        if authenticated {
            isAuthenticated = true
        }
        guard let attempt = activeAttempt else { return }

        if let required = event as? AgentEventAuthenticationRequired {
            presentBrowser(required.signInUrl, attempt: attempt)
        } else if authenticated {
            finish(attempt: attempt, error: nil)
        } else if let failure = event as? AgentEventFailure {
            finish(attempt: attempt, error: failure.message)
        }
    }

    private func presentBrowser(_ signInUrl: String, attempt: UUID) {
        guard browserSession == nil else { return }
        guard
            let url = URL(string: signInUrl),
            url.scheme?.lowercased() == "https",
            url.host != nil
        else {
            cancelLogin(attempt: attempt, message: "App Server returned an invalid ChatGPT sign-in URL.")
            return
        }
        guard let presentationAnchor = anchor ?? foregroundWindow() else {
            cancelLogin(attempt: attempt, message: "No foreground window is available for ChatGPT sign-in.")
            return
        }
        anchor = presentationAnchor

        let session = ASWebAuthenticationSession(
            url: url,
            callbackURLScheme: nil
        ) { [weak self] _, error in
            DispatchQueue.main.async {
                self?.browserDidComplete(attempt: attempt, error: error)
            }
        }
        session.presentationContextProvider = self
        browserSession = session
        if !session.start() {
            browserSession = nil
            cancelLogin(attempt: attempt, message: "Could not present ChatGPT sign-in.")
        }
    }

    private func browserDidComplete(attempt: UUID, error: Error?) {
        guard activeAttempt == attempt else { return }
        browserSession = nil
        guard let error else {
            // Codex owns the localhost OAuth callback and reports completion as an App Server event.
            return
        }
        let nsError = error as NSError
        let message = nsError.domain == ASWebAuthenticationSessionErrorDomain &&
            nsError.code == ASWebAuthenticationSessionError.canceledLogin.rawValue
            ? "ChatGPT authentication was canceled."
            : error.localizedDescription
        cancelLogin(attempt: attempt, message: message)
    }

    private func cancelLogin(
        attempt: UUID,
        message: String,
        cancellationCompletion: ((String?) -> Void)? = nil
    ) {
        finish(attempt: attempt, error: message)
        cancellationOperation?.close()
        cancellationOperation = facade.cancelAuthentication { error in
            DispatchQueue.main.async {
                cancellationCompletion?(error)
            }
        }
    }

    private func finish(attempt: UUID, error: String?) {
        guard activeAttempt == attempt else { return }
        activeAttempt = nil
        isAuthenticating = false
        browserSession?.cancel()
        browserSession = nil
        authenticationOperation?.close()
        authenticationOperation = nil
        let completion = self.completion
        self.completion = nil
        completion?(error)
    }

    private func foregroundWindow() -> UIWindow? {
        let scenes = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }
        return scenes.lazy.compactMap { scene in
            scene.windows.first(where: \.isKeyWindow)
                ?? scene.windows.first(where: { !$0.isHidden })
        }.first
    }

    deinit {
        browserSession?.cancel()
        authenticationOperation?.close()
        cancellationOperation?.close()
        observation?.close()
    }
}
