import AuthenticationServices
import CodexAgent
import UIKit

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

public final class CodexWebAuthenticationBrowser: NSObject, CodexAuthorizationBrowser {
    private let browserFactory: CodexBrowserSessionFactory
    private let anchorProvider: () -> ASPresentationAnchor?

    public override convenience init() {
        self.init(
            browserFactory: { url, completion in
                ASWebAuthenticationSession(
                    url: url,
                    callbackURLScheme: nil,
                    completionHandler: completion
                )
            },
            anchorProvider: codexForegroundWindow
        )
    }

    init(
        browserFactory: @escaping CodexBrowserSessionFactory,
        anchorProvider: @escaping () -> ASPresentationAnchor?
    ) {
        self.browserFactory = browserFactory
        self.anchorProvider = anchorProvider
        super.init()
    }

    public func open(url: CodexAuthorizationUrl) throws -> any CodexAuthorizationPresentation {
        onMain {
            guard let nativeURL = URL(string: url.value), let anchor = anchorProvider() else {
                return CodexClosedAuthorizationPresentation()
            }
            let session = browserFactory(nativeURL) { _, _ in }
            let presentation = CodexWebAuthenticationPresentation(session: session, anchor: anchor)
            (session as? ASWebAuthenticationSession)?.presentationContextProvider = presentation
            if !session.start() { presentation.close() }
            return presentation
        }
    }
}

private final class CodexWebAuthenticationPresentation: NSObject,
    CodexAuthorizationPresentation,
    ASWebAuthenticationPresentationContextProviding {
    private var session: CodexBrowserSession?
    private let anchor: ASPresentationAnchor

    init(session: CodexBrowserSession, anchor: ASPresentationAnchor) {
        self.session = session
        self.anchor = anchor
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        anchor
    }

    func close() {
        onMain {
            session?.cancel()
            session = nil
        }
    }
}

private final class CodexClosedAuthorizationPresentation: NSObject, CodexAuthorizationPresentation {
    func close() {}
}

private func onMain<T>(_ action: @MainActor () -> T) -> T {
    if Thread.isMainThread { return MainActor.assumeIsolated(action) }
    return DispatchQueue.main.sync { MainActor.assumeIsolated(action) }
}

func codexForegroundWindow() -> UIWindow? {
    let scenes = UIApplication.shared.connectedScenes
        .compactMap { $0 as? UIWindowScene }
        .filter { $0.activationState == .foregroundActive }
    return scenes.lazy.compactMap { scene in
        scene.windows.first(where: \.isKeyWindow)
            ?? scene.windows.first(where: { !$0.isHidden })
    }.first
}
