import CodexAgent
import CodexAgentAuthentication
import SwiftUI

@main
struct CodexAgentTestApp: App {
    @StateObject private var host = AgentHost()

    var body: some Scene {
        WindowGroup {
            VStack(spacing: 12) {
                Text("Local Codex iOS runtime")
                    .font(.headline)
                Text(host.status)
                    .font(.caption)
                    .multilineTextAlignment(.center)
                Button("Sign in with ChatGPT") {
                    host.authenticateWithBrowser()
                }
                Button("Run local workspace acceptance") {
                    host.runWorkspaceAcceptance()
                }
                .disabled(!host.authenticated)
                Button("Close runtime") {
                    host.close()
                }
            }
            .padding()
        }
    }
}

@MainActor
final class AgentHost: ObservableObject {
    @Published var status = "Ready"
    @Published var authenticated = false

    private let facade: IosCodexAgentFacade
    private let browserAuthentication: CodexChatGPTAuthenticationSession
    private var operation: IosCodexOperation?
    private var closed = false

    init() {
        let sandbox = NSHomeDirectory()
        let workspace = sandbox + "/Documents/CodexWorkspace"
        try? FileManager.default.createDirectory(
            atPath: workspace,
            withIntermediateDirectories: true
        )
        let configuration = IosCodexRuntimeConfiguration(
            sandboxRootPath: sandbox,
            workspacePath: workspace,
            credentialProtection: .whenUnlocked,
            codexHomePath: sandbox + "/Library/Application Support/CodexAgent",
            temporaryPath: sandbox + "/tmp/CodexAgent"
        )
        let facade = IosCodexAgentFacade(
            configuration: configuration,
            clientVersion: "0.2.0"
        )
        self.facade = facade
        browserAuthentication = CodexChatGPTAuthenticationSession(facade: facade)
        browserAuthentication.eventHandler = { [weak self] event in
            self?.handle(event)
        }
    }

    func authenticateWithBrowser() {
        status = "Opening secure ChatGPT sign-in…"
        browserAuthentication.authenticate { [weak self] error in
            if let error {
                self?.status = error
            }
        }
    }

    func runWorkspaceAcceptance() {
        status = "Waiting for the real model to read and patch the local workspace…"
        operation?.close()
        operation = facade.runWorkspaceAcceptance { [weak self] error in
            DispatchQueue.main.async {
                self?.status = error ?? "PASS: the real model read the local input file and patched the local output file with identical bytes."
            }
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        operation?.close()
        operation = nil
        browserAuthentication.close()
        facade.close()
        status = "Closed"
    }

    private func handle(_ event: AgentEvent) {
        if event is AgentEventAuthenticationRequired {
            status = "Complete ChatGPT sign-in in the secure browser sheet."
        } else if event is AgentEventAuthenticated {
            authenticated = true
            status = "Authenticated. Run the local workspace acceptance test."
        } else if let failure = event as? AgentEventFailure {
            status = "\(failure.code): \(failure.message)"
        }
    }
}
