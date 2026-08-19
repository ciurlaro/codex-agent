import AuthenticationServices
import CodexAgent
import XCTest
@testable import CodexAgentAuthentication

@MainActor
final class CodexAuthorizationBrowserTests: XCTestCase {
    func testGenericBrowserOpensTypedExternalURLAndCancelsPresentation() throws {
        let store = BrowserStore()
        let browser = CodexWebAuthenticationBrowser(
            browserFactory: { _, completion in
                let session = FakeBrowserSession(completion: completion)
                store.sessions.append(session)
                return session
            },
            anchorProvider: { ASPresentationAnchor() }
        )

        let presentation = try browser.open(
            url: CodexAuthorizationUrl.companion.external(value: "https://example.com/oauth")
        )
        XCTAssertEqual(store.sessions.count, 1)
        presentation.close()
        XCTAssertEqual(store.sessions[0].cancellationCount, 1)
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

    func cancel() {
        cancellationCount += 1
    }
}
