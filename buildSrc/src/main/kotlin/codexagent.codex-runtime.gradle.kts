tasks.register<PrepareCodexRuntimeTask>("prepareCodexRuntime") {
    codexVersion.set(providers.gradleProperty(CodexAgentBuild.Properties.CODEX_VERSION))
    archiveSha256.set(providers.gradleProperty(CodexAgentBuild.Properties.CODEX_ARCHIVE_SHA256))
    binarySha256.set(providers.gradleProperty(CodexAgentBuild.Properties.CODEX_BINARY_SHA256))
    localArchive.set(
        providers.gradleProperty(CodexAgentBuild.Properties.CODEX_ARCHIVE_FILE)
            .map { layout.projectDirectory.file(it) },
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/codex-runtime/main"))
}
