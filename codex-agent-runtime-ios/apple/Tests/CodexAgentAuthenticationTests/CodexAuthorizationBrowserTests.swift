import AuthenticationServices
import CodexAgent
import XCTest
@testable import CodexAgentAuthentication

@MainActor
final class CodexAuthorizationBrowserTests: XCTestCase {
    func testGenericBrowserOpensTypedExternalURLAndCancelsPresentation() {
        let store = BrowserStore()
        let browser = CodexWebAuthenticationBrowser(
            browserFactory: { _, completion in
                let session = FakeBrowserSession(completion: completion)
                store.sessions.append(session)
                return session
            },
            anchorProvider: { ASPresentationAnchor() }
        )

        let presentation = browser.open(
            url: CodexAuthorizationUrl.companion.external(value: "https://example.com/oauth")
        )
        XCTAssertEqual(store.sessions.count, 1)
        presentation.close()
        XCTAssertEqual(store.sessions[0].cancellationCount, 1)
    }

    func testChatGPTURLPolicyRejectsSpoofsCredentialsPortsAndHTTP() {
        XCTAssertTrue(isTrustedChatGPTURL(URL(string: "https://auth.openai.com/login")!))
        XCTAssertTrue(isTrustedChatGPTURL(URL(string: "https://chatgpt.com:443/login")!))
        XCTAssertFalse(isTrustedChatGPTURL(URL(string: "https://openai.com.evil.test/login")!))
        XCTAssertFalse(isTrustedChatGPTURL(URL(string: "https://user@openai.com/login")!))
        XCTAssertFalse(isTrustedChatGPTURL(URL(string: "https://openai.com:8443/login")!))
        XCTAssertFalse(isTrustedChatGPTURL(URL(string: "http://openai.com/login")!))
    }
}
