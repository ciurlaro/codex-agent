@_exported import CodexAgent
import AuthenticationServices
import UIKit

@MainActor
final class CodexCancellation {
    private var closeHandler: (() -> Void)?

    init(_ closeHandler: @escaping () -> Void) {
        self.closeHandler = closeHandler
    }

    func close() {
        closeHandler?()
        closeHandler = nil
    }
}

@MainActor
protocol CodexAuthenticationDriving: AnyObject {
    var authenticationState: IosCodexAuthenticationState { get }
    func observeEvents(_ observer: @escaping (AgentEvent) -> Void) -> CodexCancellation
    func observeAuthenticationState(
        _ observer: @escaping (IosCodexAuthenticationState) -> Void
    ) -> CodexCancellation
    func authenticate(_ completion: @escaping (String?) -> Void) -> CodexCancellation
    func cancelAuthentication(_ completion: @escaping (String?) -> Void) -> CodexCancellation
    func signOut(_ completion: @escaping (String?) -> Void) -> CodexCancellation
}

@MainActor
private final class IosFacadeAuthenticationDriver: CodexAuthenticationDriving {
    private let facade: IosCodexAgentFacade

    init(_ facade: IosCodexAgentFacade) {
        self.facade = facade
    }

    var authenticationState: IosCodexAuthenticationState {
        facade.authenticationState
    }

    func observeEvents(_ observer: @escaping (AgentEvent) -> Void) -> CodexCancellation {
        let observation = facade.observeEvents { event in
            DispatchQueue.main.async { observer(event) }
        }
        return CodexCancellation { observation.close() }
    }

    func observeAuthenticationState(
        _ observer: @escaping (IosCodexAuthenticationState) -> Void
    ) -> CodexCancellation {
        let observation = facade.observeAuthenticationState { state in
            DispatchQueue.main.async { observer(state) }
        }
        return CodexCancellation { observation.close() }
    }

    func authenticate(_ completion: @escaping (String?) -> Void) -> CodexCancellation {
        let operation = facade.authenticateWithChatGpt { error in
            DispatchQueue.main.async { completion(error) }
        }
        return CodexCancellation { operation.close() }
    }

    func cancelAuthentication(_ completion: @escaping (String?) -> Void) -> CodexCancellation {
        let operation = facade.cancelAuthentication { error in
            DispatchQueue.main.async { completion(error) }
        }
        return CodexCancellation { operation.close() }
    }

    func signOut(_ completion: @escaping (String?) -> Void) -> CodexCancellation {
        let operation = facade.signOut { error in
            DispatchQueue.main.async { completion(error) }
        }
        return CodexCancellation { operation.close() }
    }
}

@MainActor
protocol CodexBrowserSession: AnyObject {
    func start() -> Bool
    func cancel()
}

extension ASWebAuthenticationSession: CodexBrowserSession {}

typealias CodexBrowserSessionFactory = (
    URL,
    @escaping (URL?, Error?) -> Void
) -> CodexBrowserSession

@MainActor
public final class CodexChatGPTAuthenticationSession: NSObject,
    ASWebAuthenticationPresentationContextProviding {
    public var eventHandler: ((AgentEvent) -> Void)?
    public private(set) var isAuthenticating = false
    public private(set) var isAuthenticated = false

    private let driver: CodexAuthenticationDriving
    private let browserFactory: CodexBrowserSessionFactory
    private var eventObservation: CodexCancellation?
    private var stateObservation: CodexCancellation?
    private var authenticationOperation: CodexCancellation?
    private var cancellationOperation: CodexCancellation?
    private var signOutOperation: CodexCancellation?
    private var browserSession: CodexBrowserSession?
    private weak var anchor: ASPresentationAnchor?
    private var activeAttempt: UUID?
    private var completion: ((String?) -> Void)?
    private var closed = false

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
        if state.status == .authenticated {
            update(state)
            completion(nil)
            return
        }
        guard state.status != .closed else {
            completion("Codex Agent facade is closed.")
            return
        }

        let attempt = UUID()
        activeAttempt = attempt
        anchor = presentationAnchor
        self.completion = completion
        isAuthenticating = true

        if state.status == .authenticating {
            if let signInUrl = state.pendingSignInUrl {
                presentBrowser(signInUrl, attempt: attempt)
            }
            return
        }

        authenticationOperation = driver.authenticate { [weak self] error in
            guard let self, self.activeAttempt == attempt else { return }
            if let error {
                self.finish(attempt: attempt, error: error)
            } else if self.driver.authenticationState.status == .authenticated {
                self.finish(attempt: attempt, error: nil)
            }
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
            finish(attempt: attempt, error: "ChatGPT authentication was canceled.")
        }
        var completed = false
        let operation = driver.cancelAuthentication { [weak self] error in
            completed = true
            self?.cancellationOperation = nil
            completion?(error)
        }
        cancellationOperation = completed ? nil : operation
    }

    public func signOut(completion: @escaping (String?) -> Void) {
        guard !closed else {
            completion("ChatGPT authentication session is closed.")
            return
        }
        if let attempt = activeAttempt {
            finish(attempt: attempt, error: "ChatGPT authentication was canceled by sign-out.")
        }
        cancellationOperation?.close()
        cancellationOperation = nil
        signOutOperation?.close()
        var completed = false
        let operation = driver.signOut { [weak self] error in
            completed = true
            self?.signOutOperation = nil
            completion(error)
        }
        signOutOperation = completed ? nil : operation
    }

    public func close() {
        guard !closed else { return }
        closed = true
        let state = driver.authenticationState
        if state.status == .authenticating && state.pendingSignInUrl == nil {
            authenticationOperation?.close()
            _ = driver.cancelAuthentication { _ in }
        }
        if let attempt = activeAttempt {
            finish(attempt: attempt, error: "ChatGPT authentication session was closed.")
        }
        browserSession?.cancel()
        browserSession = nil
        authenticationOperation?.close()
        authenticationOperation = nil
        cancellationOperation?.close()
        cancellationOperation = nil
        signOutOperation?.close()
        signOutOperation = nil
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

    private func update(_ state: IosCodexAuthenticationState) {
        isAuthenticated = state.status == .authenticated
    }

    private func receive(_ event: AgentEvent) {
        eventHandler?(event)
        guard let attempt = activeAttempt else { return }

        if let required = event as? AgentEventAuthenticationRequired {
            presentBrowser(required.signInUrl, attempt: attempt)
        } else if event is AgentEventAuthenticated {
            finish(attempt: attempt, error: nil)
        } else if let failure = event as? AgentEventFailure, failure.sessionId == nil {
            finish(attempt: attempt, error: failure.message)
        }
    }

    private func presentBrowser(_ signInUrl: String, attempt: UUID) {
        guard browserSession == nil, activeAttempt == attempt else { return }
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

    private func browserDidComplete(attempt: UUID, error: Error?) {
        guard activeAttempt == attempt else { return }
        browserSession = nil
        guard let error else { return }
        let nsError = error as NSError
        let message = nsError.domain == ASWebAuthenticationSessionErrorDomain &&
            nsError.code == ASWebAuthenticationSessionError.canceledLogin.rawValue
            ? "ChatGPT authentication was canceled."
            : error.localizedDescription
        cancelFailedPresentation(attempt: attempt, message: message)
    }

    private func cancelFailedPresentation(attempt: UUID, message: String) {
        finish(attempt: attempt, error: message)
        cancellationOperation?.close()
        var completed = false
        let operation = driver.cancelAuthentication { [weak self] _ in
            completed = true
            self?.cancellationOperation = nil
        }
        cancellationOperation = completed ? nil : operation
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

}
