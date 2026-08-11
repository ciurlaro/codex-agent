import AuthenticationServices
import CodexAgent
import XCTest
@testable import CodexAgentAuthentication

@MainActor
extension CodexChatGPTAuthenticationSessionTests {
    func testWrapperRecreationAroundTheSameFacadeStartsCleanly() {
        let driver = FakeAuthenticationDriver(autoCompleteAuthenticationOperation: false)
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
        XCTAssertEqual(driver.appServerLoginCancellationCalls, 1)
        XCTAssertEqual(driver.authenticationOperationCancellations, 0)
        XCTAssertEqual(driver.authenticationOperationDetachments, 2)
        XCTAssertEqual(browsers.sessions.count, 2)
        XCTAssertNil(result)
    }

    func testCloseDuringAuthenticationCancelsEveryOwnedResource() {
        let driver = FakeAuthenticationDriver(autoCompleteAuthenticationOperation: false)
        let browsers = BrowserStore()
        let session = makeSession(driver, browsers)
        let anchor = ASPresentationAnchor()
        session.authenticate(from: anchor) { _ in }
        driver.requireBrowser("https://auth.openai.com/close")
        session.close()

        XCTAssertEqual(driver.authenticationOperationCancellations, 0)
        XCTAssertEqual(driver.authenticationOperationDetachments, 1)
        XCTAssertEqual(driver.cancellationCalls, 1)
        XCTAssertEqual(driver.appServerLoginCancellationCalls, 1)
        XCTAssertEqual(driver.authenticationOperationCancellations, 0)
        XCTAssertEqual(driver.authenticationOperationDetachments, 1)
        XCTAssertEqual(browsers.sessions.first?.cancellationCount, 1)
        XCTAssertEqual(driver.eventObserverCount, 0)
        XCTAssertEqual(driver.stateObserverCount, 0)
    }

    func testObserverWrapperCleanupDoesNotCancelOwnersLogin() {
        let driver = FakeAuthenticationDriver(autoCompleteAuthenticationOperation: false)
        let owner = makeSession(driver)
        let observer = makeSession(driver)
        owner.authenticate { _ in }
        observer.authenticate { _ in }

        observer.close()
        XCTAssertEqual(driver.cancellationCalls, 0)
        XCTAssertEqual(driver.appServerLoginCancellationCalls, 0)
        XCTAssertTrue(driver.hasActiveLogin)

        owner.close()
        XCTAssertEqual(driver.cancellationCalls, 1)
        XCTAssertEqual(driver.appServerLoginCancellationCalls, 1)
        XCTAssertEqual(driver.authenticationOperationDetachments, 1)
    }

    func testOwnedAttemptDoesNotTakeOwnershipOfANewerGeneration() {
        let driver = FakeAuthenticationDriver(autoCompleteAuthenticationOperation: false)
        let session = makeSession(driver)
        session.authenticate { _ in }

        driver.emitState(generation: 2, status: .authenticating)
        session.close()

        XCTAssertEqual(driver.cancellationCalls, 0)
        XCTAssertEqual(driver.appServerLoginCancellationCalls, 0)
        XCTAssertEqual(driver.authenticationOperationDetachments, 1)
    }

    func testSuccessfulAuthenticationDetachesWithoutLateCancellation() {
        let driver = FakeAuthenticationDriver(autoCompleteAuthenticationOperation: false)
        let session = makeSession(driver)
        var result: String?
        session.authenticate { result = $0 }
        driver.succeed()
        session.close()

        XCTAssertNil(result)
        XCTAssertEqual(driver.authenticationOperationDetachments, 1)
        XCTAssertEqual(driver.authenticationOperationCancellations, 0)
        XCTAssertEqual(driver.cancellationCalls, 0)
        XCTAssertEqual(driver.appServerLoginCancellationCalls, 0)
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

    func testObserverCancelThenImmediateCloseLeavesOwnersLoginActive() {
        let driver = FakeAuthenticationDriver(autoCompleteAuxiliaryOperations: false)
        driver.beginObserverOwnedLogin()
        let session = makeSession(driver)
        var error: String?
        session.authenticate { error = $0 }
        session.cancel()
        session.close()

        XCTAssertEqual(driver.authenticationCalls, 0)
        XCTAssertEqual(driver.cancellationCalls, 0)
        XCTAssertEqual(driver.appServerLoginCancellationCalls, 0)
        XCTAssertEqual(driver.auxiliaryOperationCancellations, 0)
        XCTAssertEqual(error, "ChatGPT authentication was canceled.")
        XCTAssertTrue(driver.hasActiveLogin)
        driver.succeed()
        XCTAssertEqual(driver.authenticationState.status, .authenticated)
    }

    func testOwnerLoginCancelThenImmediateCloseDetachesCancellation() {
        let driver = FakeAuthenticationDriver(
            autoCompleteAuthenticationOperation: false,
            autoCompleteAuxiliaryOperations: false
        )
        let session = makeSession(driver)
        session.authenticate { _ in }
        session.cancel()
        session.close()

        XCTAssertEqual(driver.authenticationOperationCancellations, 0)
        XCTAssertEqual(driver.authenticationOperationDetachments, 1)
        XCTAssertEqual(driver.cancellationCalls, 1)
        XCTAssertEqual(driver.appServerLoginCancellationCalls, 1)
        XCTAssertEqual(driver.auxiliaryOperationCancellations, 0)
        XCTAssertEqual(driver.auxiliaryOperationDetachments, 1)
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
        XCTAssertEqual(driver.auxiliaryOperationDetachments, 1)
        XCTAssertEqual(driver.appServerLoginCancellationCalls, 0)
        driver.completeSignOut()
        XCTAssertEqual(driver.authenticationState.status, .signedOut)
        XCTAssertFalse(driver.hasActiveLogin)
    }

    func testDeinitCleanupCancelsOnlyOwnedLoginAndPreservesAuxiliaryOperations() async {
        let ownerDriver = FakeAuthenticationDriver(autoCompleteAuthenticationOperation: false)
        var owner: CodexChatGPTAuthenticationSession? = makeSession(ownerDriver)
        owner?.authenticate { _ in }
        weak var releasedOwner = owner
        owner = nil
        let ownerCleanup = expectation(description: "owner deinit cleanup")
        DispatchQueue.main.async { ownerCleanup.fulfill() }
        await fulfillment(of: [ownerCleanup])

        XCTAssertNil(releasedOwner)
        XCTAssertEqual(ownerDriver.appServerLoginCancellationCalls, 1)
        XCTAssertEqual(ownerDriver.authenticationOperationCancellations, 0)
        XCTAssertEqual(ownerDriver.authenticationOperationDetachments, 1)

        let cancelDriver = FakeAuthenticationDriver(
            autoCompleteAuthenticationOperation: false,
            autoCompleteAuxiliaryOperations: false
        )
        var canceling: CodexChatGPTAuthenticationSession? = makeSession(cancelDriver)
        canceling?.authenticate { _ in }
        canceling?.cancel()
        canceling = nil
        let cancelCleanup = expectation(description: "cancel deinit cleanup")
        DispatchQueue.main.async { cancelCleanup.fulfill() }
        await fulfillment(of: [cancelCleanup])

        XCTAssertEqual(cancelDriver.appServerLoginCancellationCalls, 1)
        XCTAssertEqual(cancelDriver.authenticationOperationCancellations, 0)
        XCTAssertEqual(cancelDriver.auxiliaryOperationCancellations, 0)
        XCTAssertEqual(cancelDriver.auxiliaryOperationDetachments, 1)
        cancelDriver.completeCancellation()
        XCTAssertEqual(cancelDriver.authenticationState.status, .signedOut)

        let signOutDriver = FakeAuthenticationDriver(
            status: .authenticated,
            autoCompleteAuxiliaryOperations: false
        )
        var signingOut: CodexChatGPTAuthenticationSession? = makeSession(signOutDriver)
        signingOut?.signOut { _ in }
        signingOut = nil
        let signOutCleanup = expectation(description: "sign-out deinit cleanup")
        DispatchQueue.main.async { signOutCleanup.fulfill() }
        await fulfillment(of: [signOutCleanup])

        XCTAssertEqual(signOutDriver.signOutCalls, 1)
        XCTAssertEqual(signOutDriver.appServerLoginCancellationCalls, 0)
        XCTAssertEqual(signOutDriver.auxiliaryOperationCancellations, 0)
        XCTAssertEqual(signOutDriver.auxiliaryOperationDetachments, 1)
        signOutDriver.completeSignOut()
        XCTAssertEqual(signOutDriver.authenticationState.status, .signedOut)
    }
}
