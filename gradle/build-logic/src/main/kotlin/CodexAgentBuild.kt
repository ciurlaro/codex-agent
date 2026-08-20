internal object CodexAgentBuild {
    const val MAVEN_GROUP = "io.github.codex-agent-labs"
    const val REPOSITORY = "codex-agent-labs/codex-agent"
    const val ABI = "arm64-v8a"
    const val RUNTIME_LIBRARY = "libcodex_app_server.so"

    object Properties {
        const val CODEX_VERSION = "codexAgent.codexVersion"
        const val CODEX_ARCHIVE_SHA256 = "codexAgent.codexArchiveSha256"
        const val CODEX_BINARY_SHA256 = "codexAgent.codexBinarySha256"
        const val CODEX_ARCHIVE_FILE = "codexAgent.codexArchiveFile"
    }
}
