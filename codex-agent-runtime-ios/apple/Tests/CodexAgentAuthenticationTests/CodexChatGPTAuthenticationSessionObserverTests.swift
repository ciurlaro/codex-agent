import AuthenticationServices
import CodexAgent
import CodexAgentSQLiteTestSupport
import XCTest
@testable import CodexAgentAuthentication

@MainActor
extension CodexChatGPTAuthenticationSessionTests {
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
}
