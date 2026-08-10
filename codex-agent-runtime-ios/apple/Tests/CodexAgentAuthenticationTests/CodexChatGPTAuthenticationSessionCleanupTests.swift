import AuthenticationServices
import CodexAgent
import XCTest
@testable import CodexAgentAuthentication

@MainActor
extension CodexChatGPTAuthenticationSessionTests {
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

    func testObserverOwnedLoginCancelThenImmediateCloseDetachesCancellation() {
        let driver = FakeAuthenticationDriver(autoCompleteAuxiliaryOperations: false)
        driver.beginObserverOwnedLogin()
        let session = makeSession(driver)
        session.authenticate { _ in }
        session.cancel()
        session.close()

        XCTAssertEqual(driver.authenticationCalls, 0)
        XCTAssertEqual(driver.auxiliaryOperationCancellations, 0)
        driver.completeCancellation()
        XCTAssertEqual(driver.authenticationState.status, .signedOut)
        XCTAssertFalse(driver.hasActiveLogin)
    }

    func testOwnerLoginCancelThenImmediateCloseDetachesCancellation() {
        let driver = FakeAuthenticationDriver(autoCompleteAuxiliaryOperations: false)
        let session = makeSession(driver)
        session.authenticate { _ in }
        session.cancel()
        session.close()

        XCTAssertEqual(driver.authenticationOperationCancellations, 0)
        XCTAssertEqual(driver.auxiliaryOperationCancellations, 0)
        driver.completeCancellation()
        XCTAssertEqual(driver.authenticationState.status, .signedOut)
        XCTAssertFalse(driver.hasActiveLogin)
    }

    func testSignOutThenImmediateCloseDetachesSignOut() {
        let driver = FakeAuthenticationDriver(status: .authenticated, autoCompleteAuxiliaryOperations: false)
        let session = makeSession(driver)
        session.signOut { _ in }
        session.close()

        XCTAssertEqual(driver.auxiliaryOperationCancellations, 0)
        driver.completeSignOut()
        XCTAssertEqual(driver.authenticationState.status, .signedOut)
        XCTAssertFalse(driver.hasActiveLogin)
    }

    func testSuccessfulAuthenticationDoesNotIssueLateCancellation() {
        let driver = FakeAuthenticationDriver()
        let session = makeSession(driver)
        session.authenticate { _ in }
        driver.succeed()
        session.close()

        XCTAssertEqual(driver.authenticationOperationCancellations, 0)
        XCTAssertEqual(driver.cancellationCalls, 0)
        XCTAssertEqual(driver.authenticationState.status, .authenticated)
    }
}
