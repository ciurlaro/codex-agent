import CodexAgentAuthentication

public func makeCodexAgentRuntime(
    sandboxRootPath: String,
    workspacePath: String
) -> IosCodexRuntimeFactory {
    IosCodexRuntimeFactory(
        configuration: IosCodexRuntimeConfiguration(
            sandboxRootPath: sandboxRootPath,
            workspacePath: workspacePath,
            credentialProtection: .whenUnlocked,
            codexHomePath: sandboxRootPath + "/Library/Application Support/CodexAgent",
            temporaryPath: sandboxRootPath + "/tmp/CodexAgent"
        )
    )
}

@MainActor
public func makeChatGPTAuthenticationSession(
    facade: IosCodexAgentFacade
) -> CodexChatGPTAuthenticationSession {
    CodexChatGPTAuthenticationSession(facade: facade)
}
