import AuthenticationServices
import XCTest
@testable import CodexAgentAuthentication

@MainActor
final class CodexChatGPTAuthenticationSessionTests: XCTestCase {
    func testCancellationAllowsRetry() {
        let driver = FakeAuthenticationDriver()
        let session = makeSession(driver)
        session.authenticate { _ in }
        session.cancel()
        session.authenticate { _ in }
        XCTAssertEqual(driver.authenticationCalls, 2)
        XCTAssertEqual(driver.cancellationCalls, 1)
    }

    func testFailureAllowsRetry() {
        let driver = FakeAuthenticationDriver()
        let session = makeSession(driver)
        var firstError: String?
        session.authenticate { firstError = $0 }
        driver.fail("Login failed")
        session.authenticate { _ in }
        XCTAssertEqual(firstError, "Login failed")
        XCTAssertEqual(driver.authenticationCalls, 2)
    }

    func testSignOutAllowsAuthentication() {
        let driver = FakeAuthenticationDriver(status: .authenticated)
        let session = makeSession(driver)
        XCTAssertTrue(session.isAuthenticated)
        session.signOut { XCTAssertNil($0) }
        session.authenticate { _ in }
        XCTAssertFalse(session.isAuthenticated)
        XCTAssertEqual(driver.signOutCalls, 1)
        XCTAssertEqual(driver.authenticationCalls, 1)
    }

    func testRecreatedWrapperResumesPendingBrowserLogin() {
        let driver = FakeAuthenticationDriver()
        let browsers = BrowserStore()
        let anchor = ASPresentationAnchor()
        let first = makeSession(driver, browsers)
        first.authenticate(from: anchor) { _ in }
        driver.requireBrowser("https://auth.openai.com/resume")
        XCTAssertEqual(browsers.sessions.count, 1)
        first.close()

        let second = makeSession(driver, browsers)
        var result: String?
        second.authenticate(from: anchor) { result = $0 }
        XCTAssertEqual(driver.authenticationCalls, 1)
        XCTAssertEqual(browsers.sessions.count, 2)
        driver.succeed()
        XCTAssertNil(result)
        XCTAssertTrue(second.isAuthenticated)
    }

    func testCloseBeforeBrowserURLCancelsOwnedWork() {
        let driver = FakeAuthenticationDriver()
        let session = makeSession(driver)
        session.authenticate { _ in }
        session.close()
        session.close()
        XCTAssertEqual(driver.authenticationOperationCancellations, 1)
        XCTAssertEqual(driver.cancellationCalls, 1)
        XCTAssertEqual(driver.eventObserverCount, 0)
        XCTAssertEqual(driver.stateObserverCount, 0)
    }

    func testRuntimeFailureClearsAuthenticatedState() {
        let driver = FakeAuthenticationDriver(status: .authenticated)
        let session = makeSession(driver)
        XCTAssertTrue(session.isAuthenticated)
        driver.fail("Runtime disconnected", code: "event_stream")
        XCTAssertFalse(session.isAuthenticated)
    }

    func testRepeatedCancelAndCloseAreHarmless() {
        let driver = FakeAuthenticationDriver()
        let session = makeSession(driver)
        session.cancel()
        session.cancel()
        session.close()
        session.close()
        session.cancel()
        XCTAssertEqual(driver.cancellationCalls, 0)
    }

    private func makeSession(
        _ driver: FakeAuthenticationDriver,
        _ suppliedBrowsers: BrowserStore? = nil
    ) -> CodexChatGPTAuthenticationSession {
        let browsers = suppliedBrowsers ?? BrowserStore()
        return CodexChatGPTAuthenticationSession(
            driver: driver,
            browserFactory: { _, _ in
                let session = FakeBrowserSession()
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
    private(set) var authenticationCalls = 0
    private(set) var cancellationCalls = 0
    private(set) var signOutCalls = 0
    private(set) var authenticationOperationCancellations = 0

    var eventObserverCount: Int { eventObservers.count }
    var stateObserverCount: Int { stateObservers.count }

    init(status: IosCodexAuthenticationStatus = .signedOut) {
        authenticationState = IosCodexAuthenticationState(status: status, pendingSignInUrl: nil)
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

    func authenticate(_ completion: @escaping (String?) -> Void) -> CodexCancellation {
        authenticationCalls += 1
        setState(.authenticating)
        completion(nil)
        return CodexCancellation { [weak self] in
            self?.authenticationOperationCancellations += 1
        }
    }

    func cancelAuthentication(_ completion: @escaping (String?) -> Void) -> CodexCancellation {
        cancellationCalls += 1
        setState(.signedOut)
        completion(nil)
        return CodexCancellation {}
    }

    func signOut(_ completion: @escaping (String?) -> Void) -> CodexCancellation {
        signOutCalls += 1
        setState(.signedOut)
        completion(nil)
        return CodexCancellation {}
    }

    func requireBrowser(_ url: String) {
        authenticationState = IosCodexAuthenticationState(
            status: .authenticating,
            pendingSignInUrl: url
        )
        notifyState()
        let event = AgentEventAuthenticationRequired(signInUrl: url)
        eventObservers.values.forEach { $0(event) }
    }

    func succeed() {
        setState(.authenticated)
        eventObservers.values.forEach { $0(AgentEventAuthenticated.shared) }
    }

    func fail(_ message: String, code: String = "authentication_failed") {
        setState(.signedOut)
        let event = AgentEventFailure(
            sessionId: nil,
            code: code,
            message: message,
            recoverable: true
        )
        eventObservers.values.forEach { $0(event) }
    }

    private func setState(_ status: IosCodexAuthenticationStatus) {
        authenticationState = IosCodexAuthenticationState(status: status, pendingSignInUrl: nil)
        notifyState()
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
    private(set) var cancellationCount = 0
    func start() -> Bool { true }
    func cancel() { cancellationCount += 1 }
}
