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
final class IosFacadeAuthenticationDriver: CodexAuthenticationDriving {
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
