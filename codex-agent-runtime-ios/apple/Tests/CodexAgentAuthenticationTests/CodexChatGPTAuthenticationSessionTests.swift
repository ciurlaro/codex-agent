import AuthenticationServices
import CodexAgent
import CodexAgentSQLiteTestSupport
import XCTest
@testable import CodexAgentAuthentication

@MainActor
final class CodexChatGPTAuthenticationSessionTests: XCTestCase {
    func testBundledSQLiteWalConcurrencyDurabilityAndRestart() {
        let path = FileManager.default.temporaryDirectory
            .appendingPathComponent("codex-agent-sqlite-\(UUID().uuidString).db")
            .path
        var error = [CChar](repeating: 0, count: 1024)
        let result = path.withCString { databasePath in
            codex_agent_run_sqlite_tests(databasePath, &error, error.count)
        }
        XCTAssertEqual(result, 0, String(cString: error))
    }

    func testMultipleObserversReceiveTheSameEvents() {
        let driver = FakeAuthenticationDriver()
        let first = makeSession(driver)
        let second = makeSession(driver)
        var firstEvents: [AgentEvent] = []
        var secondEvents: [AgentEvent] = []
        first.eventHandler = { firstEvents.append($0) }
        second.eventHandler = { secondEvents.append($0) }

        driver.requireBrowser("https://auth.openai.com/shared")
        driver.fail("Runtime disconnected", code: "event_stream")

        XCTAssertEqual(firstEvents.count, 2)
        XCTAssertEqual(secondEvents.count, 2)
        XCTAssertTrue(firstEvents[0] is AgentEventAuthenticationRequired)
        XCTAssertTrue(secondEvents[0] is AgentEventAuthenticationRequired)
        XCTAssertTrue(firstEvents[1] is AgentEventFailure)
        XCTAssertTrue(secondEvents[1] is AgentEventFailure)
    }

    func testCancellationFollowedImmediatelyByRetryIgnoresStaleCompletion() {
        let driver = FakeAuthenticationDriver(autoCompleteAuxiliaryOperations: false)
        let session = makeSession(driver)
        var retryResult: String?
        session.authenticate { _ in }
        session.cancel()
        session.authenticate { retryResult = $0 }
        driver.completeCancellation()
        driver.succeed()

        XCTAssertEqual(driver.authenticationCalls, 2)
        XCTAssertEqual(driver.cancellationCalls, 1)
        XCTAssertNil(retryResult)
        XCTAssertTrue(session.isAuthenticated)
    }

    func testFailureFollowedByRetryPreservesServerMessage() {
        let driver = FakeAuthenticationDriver()
        let session = makeSession(driver)
        var firstError: String?
        var retryResult: String?
        session.authenticate { firstError = $0 }
        driver.fail("Server supplied login failure")
        session.authenticate { retryResult = $0 }
        driver.succeed()

        XCTAssertEqual(firstError, "Server supplied login failure")
        XCTAssertNil(retryResult)
        XCTAssertEqual(driver.authenticationCalls, 2)
    }

    func testSignOutFollowedImmediatelyByAuthentication() {
        let driver = FakeAuthenticationDriver(
            status: .authenticated,
            autoCompleteAuxiliaryOperations: false
        )
        let session = makeSession(driver)
        var result: String?
        session.signOut { _ in }
        session.authenticate { result = $0 }
        driver.completeSignOut()
        driver.succeed()

        XCTAssertEqual(driver.signOutCalls, 1)
        XCTAssertEqual(driver.authenticationCalls, 1)
        XCTAssertNil(result)
        XCTAssertTrue(session.isAuthenticated)
    }

    func testWrapperRecreationAroundTheSameFacadeStartsCleanly() {
        let driver = FakeAuthenticationDriver()
        let browsers = BrowserStore()
        let anchor = ASPresentationAnchor()
        var first: CodexChatGPTAuthenticationSession? = makeSession(driver, browsers)
        first?.authenticate(from: anchor) { _ in }
        driver.requireBrowser("https://auth.openai.com/resume")
        first?.close()
        first = nil

        let second = makeSession(driver, browsers)
        var result: String?
        second.authenticate(from: anchor) { result = $0 }
        driver.requireBrowser("https://auth.openai.com/retry")
        driver.succeed()

        XCTAssertEqual(driver.authenticationCalls, 2)
        XCTAssertEqual(driver.cancellationCalls, 1)
        XCTAssertEqual(browsers.sessions.count, 2)
        XCTAssertNil(result)
    }

    func testCloseDuringAuthenticationCancelsEveryOwnedResource() {
        let driver = FakeAuthenticationDriver()
        let browsers = BrowserStore()
        let session = makeSession(driver, browsers)
        let anchor = ASPresentationAnchor()
        session.authenticate(from: anchor) { _ in }
        driver.requireBrowser("https://auth.openai.com/close")
        session.close()

        XCTAssertEqual(driver.authenticationOperationCancellations, 0)
        XCTAssertEqual(driver.authenticationOperationDetachments, 1)
        XCTAssertEqual(driver.cancellationCalls, 1)
        XCTAssertEqual(browsers.sessions.first?.cancellationCount, 1)
        XCTAssertEqual(driver.eventObserverCount, 0)
        XCTAssertEqual(driver.stateObserverCount, 0)
    }

    func testObserverWrapperCleanupDoesNotCancelOwnersLogin() {
        let driver = FakeAuthenticationDriver()
        let owner = makeSession(driver)
        let observer = makeSession(driver)
        owner.authenticate { _ in }
        observer.authenticate { _ in }

        observer.close()
        XCTAssertEqual(driver.cancellationCalls, 0)

        owner.close()
        XCTAssertEqual(driver.cancellationCalls, 1)
    }

    func testOwnedAttemptDoesNotTakeOwnershipOfANewerGeneration() {
        let driver = FakeAuthenticationDriver()
        let session = makeSession(driver)
        session.authenticate { _ in }

        driver.emitState(generation: 2, status: .authenticating)
        session.close()

        XCTAssertEqual(driver.cancellationCalls, 0)
    }

    func testSuccessfulAuthenticationDetachesWithoutLateCancellation() {
        let driver = FakeAuthenticationDriver()
        let session = makeSession(driver)
        var result: String?
        session.authenticate { result = $0 }
        driver.succeed()
        session.close()

        XCTAssertNil(result)
        XCTAssertEqual(driver.authenticationOperationDetachments, 1)
        XCTAssertEqual(driver.authenticationOperationCancellations, 0)
        XCTAssertEqual(driver.cancellationCalls, 0)
    }

    func testRepeatedCloseAndCancellationCallsAreIdempotent() {
        let driver = FakeAuthenticationDriver()
        let session = makeSession(driver)
        session.cancel()
        session.cancel()
        session.close()
        session.close()
        session.cancel()

        XCTAssertEqual(driver.cancellationCalls, 0)
        XCTAssertEqual(driver.eventObserverCount, 0)
        XCTAssertEqual(driver.stateObserverCount, 0)
    }

    func testWrapperACancelingFinishesWrapperBWithoutCompetingForEvents() {
        let driver = FakeAuthenticationDriver()
        let first = makeSession(driver)
        let second = makeSession(driver)
        var firstError: String?
        var secondError: String?
        first.authenticate { firstError = $0 }
        second.authenticate { secondError = $0 }
        first.cancel()

        XCTAssertEqual(driver.authenticationCalls, 1)
        XCTAssertNotNil(firstError)
        XCTAssertNotNil(secondError)
        XCTAssertFalse(first.isAuthenticating)
        XCTAssertFalse(second.isAuthenticating)
    }

    func testFacadeClosingWhileWrapperWaitsFinishesAttempt() {
        let driver = FakeAuthenticationDriver()
        let session = makeSession(driver)
        var result: String?
        session.authenticate { result = $0 }
        driver.closeFacade()

        XCTAssertEqual(result, "Codex Agent facade is closed.")
        XCTAssertFalse(session.isAuthenticating)
        XCTAssertFalse(session.isAuthenticated)
    }

    func testBrowserCancellationAllowsImmediateRetry() async {
        let driver = FakeAuthenticationDriver()
        let browsers = BrowserStore()
        let session = makeSession(driver, browsers)
        let anchor = ASPresentationAnchor()
        let canceled = expectation(description: "browser cancellation completed")
        var firstError: String?
        session.authenticate(from: anchor) {
            firstError = $0
            canceled.fulfill()
        }
        driver.requireBrowser("https://auth.openai.com/browser")
        browsers.sessions[0].complete(
            NSError(
                domain: ASWebAuthenticationSessionErrorDomain,
                code: ASWebAuthenticationSessionError.canceledLogin.rawValue
            )
        )
        await fulfillment(of: [canceled])
        session.authenticate(from: anchor) { _ in }

        XCTAssertEqual(firstError, "ChatGPT authentication was canceled.")
        XCTAssertEqual(driver.authenticationCalls, 2)
    }

    func testAlreadyAuthenticatedCompletesWithoutStartingAnotherLogin() {
        let driver = FakeAuthenticationDriver(status: .authenticated)
        let session = makeSession(driver)
        var result: String? = "not completed"
        session.authenticate { result = $0 }

        XCTAssertNil(result)
        XCTAssertTrue(session.isAuthenticated)
        XCTAssertEqual(driver.authenticationCalls, 0)
    }

    func testDelayedCancelAndSignOutCompletionsCannotOverwriteNewestAttempt() {
        let driver = FakeAuthenticationDriver(autoCompleteAuxiliaryOperations: false)
        let session = makeSession(driver)
        var newestResult: String?
        session.authenticate { _ in }
        session.cancel()
        session.authenticate { _ in }
        session.signOut { _ in }
        session.authenticate { newestResult = $0 }
        driver.completeCancellation()
        driver.completeSignOut()
        driver.succeed()

        XCTAssertEqual(driver.authenticationCalls, 3)
        XCTAssertNil(newestResult)
        XCTAssertTrue(session.isAuthenticated)
    }

    func testStaleTerminalCompletionCannotOverwriteNewerGeneration() {
        let driver = FakeAuthenticationDriver(autoCompleteAuxiliaryOperations: false)
        let session = makeSession(driver)
        var newestResult: String?
        session.authenticate { _ in }
        session.cancel()
        session.authenticate { newestResult = $0 }

        driver.emitState(
            generation: 1,
            status: .signedOut,
            terminalReason: "stale failure"
        )
        XCTAssertTrue(session.isAuthenticating)
        XCTAssertFalse(session.isAuthenticated)

        driver.succeed()
        XCTAssertNil(newestResult)
        XCTAssertTrue(session.isAuthenticated)
    }

    func testDirectOperationHandleCancellationIsActiveAndIdempotent() {
        var cancellations = 0
        var detachments = 0
        let handle = CodexOperationHandle(
            generation: 17,
            cancel: { cancellations += 1 },
            detach: { detachments += 1 }
        )
        handle.cancel()
        handle.cancel()
        handle.detach()

        XCTAssertEqual(handle.generation, 17)
        XCTAssertEqual(cancellations, 1)
        XCTAssertEqual(detachments, 0)
    }

    private func makeSession(
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
private final class FakeAuthenticationDriver: CodexAuthenticationDriving {
    private(set) var authenticationState: IosCodexAuthenticationState
    private var eventObservers: [UUID: (AgentEvent) -> Void] = [:]
    private var stateObservers: [UUID: (IosCodexAuthenticationState) -> Void] = [:]
    private var generation: Int64 = 0
    private var pendingCancellations: [(Int64, (String?) -> Void)] = []
    private var pendingSignOuts: [(Int64, (String?) -> Void)] = []
    private let autoCompleteAuxiliaryOperations: Bool
    private(set) var authenticationCalls = 0
    private(set) var cancellationCalls = 0
    private(set) var signOutCalls = 0
    private(set) var authenticationOperationCancellations = 0
    private(set) var authenticationOperationDetachments = 0

    var eventObserverCount: Int { eventObservers.count }
    var stateObserverCount: Int { stateObservers.count }

    init(
        status: IosCodexAuthenticationStatus = .signedOut,
        autoCompleteAuxiliaryOperations: Bool = true
    ) {
        authenticationState = IosCodexAuthenticationState(
            status: status,
            generation: 0,
            pendingSignInUrl: nil,
            terminalReason: nil
        )
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
        authenticationCalls += 1
        setState(.authenticating, generation: generation)
        completion(nil)
        return CodexOperationHandle(
            generation: generation,
            cancel: { [weak self] in self?.authenticationOperationCancellations += 1 },
            detach: { [weak self] in self?.authenticationOperationDetachments += 1 }
        )
    }

    func cancelAuthentication(_ completion: @escaping (String?) -> Void) -> CodexOperationHandle {
        generation += 1
        cancellationCalls += 1
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

    func succeed() {
        eventObservers.values.forEach { $0(AgentEventAuthenticated.shared) }
        setState(.authenticated, generation: generation)
    }

    func fail(_ message: String, code: String = "authentication_failed") {
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
        CodexOperationHandle(generation: generation, cancel: {}, detach: {})
    }

    private func notifyState() {
        stateObservers.values.forEach { $0(authenticationState) }
    }
}

@MainActor
private final class BrowserStore {
    var sessions: [FakeBrowserSession] = []
}

@MainActor
private final class FakeBrowserSession: CodexBrowserSession {
    private let completion: (URL?, Error?) -> Void
    private(set) var cancellationCount = 0

    init(completion: @escaping (URL?, Error?) -> Void) {
        self.completion = completion
    }

    func start() -> Bool { true }
    func cancel() { cancellationCount += 1 }
    func complete(_ error: Error?) { completion(nil, error) }
}
