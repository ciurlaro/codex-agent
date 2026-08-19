import CodexAgent
import CodexAgentAuthentication
import CodexAgentObservation
import CodexAgentSwiftSupport
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
                Text(host.authentication)
                    .font(.caption2)
                Text(host.conversationState)
                    .font(.caption2)
                Button("Use Documents workspace") {
                    host.selectWorkspace()
                }
                Button("Sign in with ChatGPT") {
                    host.authenticate()
                }
                .disabled(!host.canAuthenticate)
                Button("Run local workspace acceptance") {
                    host.runWorkspaceAcceptance()
                }
                .disabled(!host.canRunAcceptance)
                Button("Close host") {
                    host.close()
                }
            }
            .padding()
        }
    }
}

@MainActor
final class AgentHost: ObservableObject {
    @Published var status = "Starting…"
    @Published var authentication = "Authentication: unavailable"
    @Published var conversationState = "Conversation: unavailable"
    @Published var canAuthenticate = false
    @Published var canRunAcceptance = false

    private let host: CodexHost
    private let workspaceURL: URL
    private var stateObservation: Task<Void, Never>?
    private var authenticationObservation: Task<Void, Never>?
    private var activeConversationObservation: Task<Void, Never>?
    private var conversationObservation: Task<Void, Never>?
    private var pendingOperation: Task<Void, Never>?
    private var agent: CodexAgent?
    private var conversation: CodexConversation?
    private var closed = false

    init() {
        let sandbox = NSHomeDirectory()
        let workspace = sandbox + "/Documents/CodexWorkspace"
        try? FileManager.default.createDirectory(
            atPath: workspace,
            withIntermediateDirectories: true
        )
        workspaceURL = URL(fileURLWithPath: workspace)
        let platform = IosCodexPlatform(
            sandboxRootPath: sandbox,
            credentialProtection: .whenUnlocked,
            authorizationBrowser: CodexWebAuthenticationBrowser(),
            codexHomePath: sandbox + "/Library/Application Support/CodexAgent",
            storageRoots: nil
        )
        let codexHost = CodexHost(
            platform: platform,
            clientInfo: CodexClientInfo(
                name: "codex-agent-test-app",
                title: "Codex Agent Test App",
                version: "0.2.0"
            )
        )
        host = codexHost
        stateObservation = Task { [weak self, codexHost] in
            for await state in codexHost.states {
                self?.handle(state)
            }
        }
        run { try await codexHost.start() }
    }

    func selectWorkspace() {
        run { [self] in
            try await host.selectWorkspace(
                selection: IosCodexWorkspaceSelection(url: workspaceURL)
            )
        }
    }

    func authenticate() {
        guard let agent else { return }
        run { try await agent.authenticate() }
    }

    func runWorkspaceAcceptance() {
        guard let agent else { return }
        run { [self] in
            let input = workspaceURL.appendingPathComponent("acceptance-input.txt")
            let output = workspaceURL.appendingPathComponent("acceptance-output.txt")
            try Self.acceptanceContent.write(to: input, atomically: true, encoding: .utf8)
            try "Waiting for Codex\n".write(to: output, atomically: true, encoding: .utf8)

            let conversation: CodexConversation
            if let active = self.conversation {
                conversation = active
            } else {
                conversation = try await agent.openConversation(
                    conversationId: nil,
                    settings: AgentConversationSettings(
                        approvalPreset: .never,
                        serviceTier: nil
                    )
                )
                observe(conversation)
            }
            status = "Waiting for the real model to read and patch the local workspace…"
            try await conversation.send(
                "Use read_file to read acceptance-input.txt, then use apply_patch to replace the complete contents of acceptance-output.txt with exactly what you read. Do not include extra text in the file."
            )
            try await waitUntilTurnCompletes(conversation)
            guard try String(contentsOf: output, encoding: .utf8) == Self.acceptanceContent else {
                throw WorkspaceAcceptanceError.unexpectedOutput
            }
            status = "PASS: the real model read the local input file and patched the local output file with identical bytes."
        }
    }

    private func waitUntilTurnCompletes(_ conversation: CodexConversation) async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                for await state in conversation.states {
                    if state.status == .ready { return }
                    if state.status == .failed {
                        throw WorkspaceAcceptanceError.turnFailed(
                            state.failure?.message ?? "Codex turn failed"
                        )
                    }
                    if state.status == .closed { throw WorkspaceAcceptanceError.conversationClosed }
                }
                throw WorkspaceAcceptanceError.conversationClosed
            }
            group.addTask {
                try await Task.sleep(nanoseconds: 180_000_000_000)
                throw WorkspaceAcceptanceError.timedOut
            }
            _ = try await group.next()
            group.cancelAll()
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        stateObservation?.cancel()
        stateObservation = nil
        pendingOperation?.cancel()
        pendingOperation = nil
        deactivateAgent()
        pendingOperation = Task {
            try? await host.close()
            status = "Closed"
        }
    }

    private func run(_ operation: @escaping () async throws -> Void) {
        pendingOperation?.cancel()
        pendingOperation = Task { [weak self] in
            do {
                try await operation()
            } catch is CancellationError {
                // A newer operation or close owns the lifecycle now.
            } catch {
                self?.status = error.codexFailure?.message ?? error.localizedDescription
            }
        }
    }

    private func handle(_ state: any CodexHostState) {
        status = Self.describe(state)
        if let ready = state as? CodexHostStateReady {
            observe(ready.agent)
        } else {
            deactivateAgent()
        }
    }

    private func observe(_ agent: CodexAgent) {
        if let current = self.agent, current === agent { return }
        deactivateAgent()
        self.agent = agent
        canAuthenticate = true
        authenticationObservation = Task { [weak self, agent] in
            for await state in agent.authenticationStates {
                self?.authentication = "Authentication: \(state.status.name.lowercased())"
                self?.canRunAcceptance = state.status == .authenticated
            }
        }
        activeConversationObservation = Task { [weak self, agent] in
            for await conversation in agent.activeConversations {
                self?.observe(conversation)
            }
        }
    }

    private func observe(_ conversation: CodexConversation?) {
        if let current = self.conversation, let conversation, current === conversation { return }
        conversationObservation?.cancel()
        conversationObservation = nil
        self.conversation = conversation
        guard let conversation else { return }
        conversationObservation = Task { [weak self, conversation] in
            for await state in conversation.states {
                self?.conversationState = "Conversation: \(state.status.name.lowercased())"
            }
        }
    }

    private func deactivateAgent() {
        authenticationObservation?.cancel()
        activeConversationObservation?.cancel()
        conversationObservation?.cancel()
        authenticationObservation = nil
        activeConversationObservation = nil
        conversationObservation = nil
        agent = nil
        conversation = nil
        authentication = "Authentication: unavailable"
        conversationState = "Conversation: unavailable"
        canAuthenticate = false
        canRunAcceptance = false
    }

    private static func describe(_ state: any CodexHostState) -> String {
        switch state {
        case is CodexHostStateNew: "New"
        case is CodexHostStateRestoring: "Restoring workspace…"
        case let state as CodexHostStateWorkspaceRequired: state.requirement.message
        case let state as CodexHostStatePreparing: "Preparing \(state.workspace.displayName)…"
        case is CodexHostStateReady: "Ready"
        case let state as CodexHostStateFailed: state.failure.message
        case is CodexHostStateClosed: "Closed"
        default: "Unknown state"
        }
    }

    private static let acceptanceContent = "ChatGPT browser-login local workspace acceptance\n"
}

private enum WorkspaceAcceptanceError: LocalizedError {
    case unexpectedOutput
    case turnFailed(String)
    case conversationClosed
    case timedOut

    var errorDescription: String? {
        switch self {
        case .unexpectedOutput: "Model wrote unexpected acceptance output"
        case let .turnFailed(message): message
        case .conversationClosed: "Conversation closed before acceptance completed"
        case .timedOut: "Timed out waiting for workspace acceptance"
        }
    }
}
