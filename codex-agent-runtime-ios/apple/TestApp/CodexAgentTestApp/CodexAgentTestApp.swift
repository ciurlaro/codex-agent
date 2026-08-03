import CodexAgent
import SwiftUI

@main
struct CodexAgentTestApp: App {
    @StateObject private var host = AgentHost()
    @State private var apiKey = ""

    var body: some Scene {
        WindowGroup {
            VStack(spacing: 12) {
                Text("Local Codex iOS runtime")
                    .font(.headline)
                Text(host.status)
                    .font(.caption)
                    .multilineTextAlignment(.center)
                SecureField("OpenAI API key", text: $apiKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Start with API key") {
                    host.authenticate(apiKey: apiKey)
                }
                .disabled(apiKey.isEmpty)
                Button("Start device-code login") {
                    host.authenticateWithDeviceCode()
                }
            }
            .padding()
            .onAppear(perform: host.startObserving)
        }
    }
}

final class AgentHost: ObservableObject {
    @Published var status = "Ready"

    private let facade: IosCodexAgentFacade
    private var observation: IosCodexObservation?
    private var operation: IosCodexOperation?

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
            codexHomePath: sandbox + "/Library/Application Support/CodexAgent",
            temporaryPath: sandbox + "/tmp/CodexAgent"
        )
        facade = IosCodexAgentFacade(
            configuration: configuration,
            clientVersion: "0.2.0"
        )
    }

    func startObserving() {
        guard observation == nil else { return }
        observation = facade.observeEvents { [weak self] event in
            DispatchQueue.main.async {
                self?.status = String(describing: event)
            }
        }
    }

    func authenticate(apiKey: String) {
        status = "Starting embedded runtime…"
        operation?.close()
        operation = facade.authenticateWithApiKey(apiKey: apiKey) { [weak self] error in
            DispatchQueue.main.async {
                self?.status = error ?? "Authenticated"
            }
        }
    }

    func authenticateWithDeviceCode() {
        status = "Starting device-code login…"
        operation?.close()
        operation = facade.authenticateWithDeviceCode { [weak self] error in
            guard let error else { return }
            DispatchQueue.main.async {
                self?.status = error
            }
        }
    }

    deinit {
        operation?.close()
        observation?.close()
        facade.close()
    }
}
