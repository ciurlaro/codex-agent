import CodexAgent
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
final class CodexOperationHandle {
    let generation: Int64
    private var cancelHandler: (() -> Void)?
    private var detachHandler: (() -> Void)?

    init(
        generation: Int64,
        cancel: @escaping () -> Void,
        detach: @escaping () -> Void
    ) {
        self.generation = generation
        cancelHandler = cancel
        detachHandler = detach
    }

    func cancel() {
        guard let cancelHandler else { return }
        self.cancelHandler = nil
        detachHandler = nil
        cancelHandler()
    }

    func detach() {
        guard let detachHandler else { return }
        cancelHandler = nil
        self.detachHandler = nil
        detachHandler()
    }
}

@MainActor
protocol CodexAuthenticationDriving: AnyObject {
    var authenticationState: IosCodexAuthenticationState { get }
    func observeEvents(_ observer: @escaping (AgentEvent) -> Void) -> CodexCancellation
    func observeAuthenticationState(
        _ observer: @escaping (IosCodexAuthenticationState) -> Void
    ) -> CodexCancellation
    func authenticate(_ completion: @escaping (String?) -> Void) -> CodexOperationHandle
    func cancelAuthentication(_ completion: @escaping (String?) -> Void) -> CodexOperationHandle
    func signOut(_ completion: @escaping (String?) -> Void) -> CodexOperationHandle
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

    func authenticate(_ completion: @escaping (String?) -> Void) -> CodexOperationHandle {
        let operation = facade.authenticateWithChatGpt { error in
            DispatchQueue.main.async { completion(error) }
        }
        return CodexOperationHandle(
            generation: operation.generation,
            cancel: { operation.cancel() },
            detach: { operation.close() }
        )
    }

    func cancelAuthentication(_ completion: @escaping (String?) -> Void) -> CodexOperationHandle {
        let operation = facade.cancelAuthentication { error in
            DispatchQueue.main.async { completion(error) }
        }
        return CodexOperationHandle(
            generation: operation.generation,
            cancel: { operation.cancel() },
            detach: { operation.close() }
        )
    }

    func signOut(_ completion: @escaping (String?) -> Void) -> CodexOperationHandle {
        let operation = facade.signOut { error in
            DispatchQueue.main.async { completion(error) }
        }
        return CodexOperationHandle(
            generation: operation.generation,
            cancel: { operation.cancel() },
            detach: { operation.close() }
        )
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
    private var authenticationOperation: CodexOperationHandle?
    private var cancellationOperation: CodexOperationHandle?
    private var cancellationToken: UUID?
    private var signOutOperation: CodexOperationHandle?
    private var signOutToken: UUID?
    private var browserSession: CodexBrowserSession?
    private weak var anchor: ASPresentationAnchor?
    private struct Attempt {
        let id: UUID
        var generation: Int64?
        var ownsLogin: Bool
    }

    private var activeAttempt: Attempt?
    private var completion: ((String?) -> Void)?
    private var lastStateGeneration: Int64 = 0
    private var lastStateStatus: IosCodexAuthenticationStatus?
    private var lastPendingSignInUrl: String?
    private var lastTerminalReason: String?
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
            ownsActiveLogin = false
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
        let shouldCancelLogin = activeAttempt?.ownsLogin == true &&
            cancellationToken == nil && signOutToken == nil
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
            _ = driver.cancelAuthentication { _ in }
        }
        if let attempt = activeAttempt {
            finish(
                attempt: attempt.id,
                error: "ChatGPT authentication session was closed.",
                cancelOperation: ownsLogin
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

    private func record(_ state: IosCodexAuthenticationState) -> Bool {
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

    private func receive(_ event: AgentEvent) {
        eventHandler?(event)
    }

    private func presentBrowser(_ signInUrl: String, attempt: UUID) {
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

    private func browserDidComplete(attempt: UUID, error: Error?) {
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

    private func cancelFailedPresentation(attempt: UUID, message: String) {
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

    private func finish(
        attempt: UUID,
        error: String?,
        cancelOperation: Bool = false
    ) {
        guard activeAttempt?.id == attempt else { return }
        activeAttempt = nil
        ownsActiveLogin = false
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
