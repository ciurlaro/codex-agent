import CodexAgent
import XCTest
@testable import CodexAgentAuthentication

@MainActor
final class CodexChatGPTAuthenticationSessionTests: XCTestCase {
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

    func testStaleAuxiliaryCompletionAfterNewGenerationDoesNotSignOut() {
        let driver = FakeAuthenticationDriver(autoCompleteAuxiliaryOperations: false)
        let session = makeSession(driver)
        session.authenticate { _ in }
        session.cancel()
        session.authenticate { _ in }
        driver.completeCancellation()

        XCTAssertEqual(driver.authenticationState.status, .authenticating)
        XCTAssertTrue(driver.hasActiveLogin)
        driver.succeed()
        XCTAssertEqual(driver.authenticationState.status, .authenticated)
    }
}
