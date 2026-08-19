package io.github.ciurlaro.codexmobile.appserver.runtime

internal enum class RuntimeKernel { LINUX }

internal enum class RuntimeArchitecture { AARCH64 }

internal data class RuntimeEnvironment(
    val kernel: RuntimeKernel,
    val architecture: RuntimeArchitecture,
    val supportsStaticElf: Boolean,
)

internal data class AppServerRuntimeDistribution(
    val appServerVersion: String,
    val upstreamRevision: String,
    val schemaSha256: String,
    val targetTriple: String,
    val architecture: RuntimeArchitecture,
    val archiveSha256: String,
    val binarySha256: String,
) {
    init {
        require(appServerVersion.isNotBlank())
        require(upstreamRevision.matches(SHA256_OR_GIT_REVISION))
        require(schemaSha256.matches(SHA256))
        require(targetTriple.isNotBlank())
        require(archiveSha256.matches(SHA256))
        require(binarySha256.matches(SHA256))
    }

    fun requireCompatible(
        protocolVersion: String,
        protocolRevision: String,
        protocolSchemaSha256: String,
        environment: RuntimeEnvironment,
    ) {
        require(protocolVersion == appServerVersion) { "App Server client/runtime version mismatch" }
        require(protocolRevision == upstreamRevision) { "App Server client/runtime revision mismatch" }
        require(protocolSchemaSha256 == schemaSha256) { "App Server client/runtime schema mismatch" }
        require(environment.kernel == RuntimeKernel.LINUX) { "App Server runtime requires Linux" }
        require(environment.architecture == architecture) { "App Server runtime architecture mismatch" }
        require(environment.supportsStaticElf) { "App Server runtime requires static ELF execution" }
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
        val SHA256_OR_GIT_REVISION = Regex("[a-f0-9]{40}|[a-f0-9]{64}")
    }
}

internal object CodexMobileAppServerRuntime {
    val DISTRIBUTION = AppServerRuntimeDistribution(
        appServerVersion = "0.145.0",
        upstreamRevision = "25af12f7e61572b0bc18ddb1008be543b91519b0",
        schemaSha256 = "32b26f2ab3fb7a4a409db958f438f48b0ef106e3a01468f8618fdf65bc823cc4",
        targetTriple = "aarch64-unknown-linux-musl",
        architecture = RuntimeArchitecture.AARCH64,
        archiveSha256 = "3a185f6a1e2ec3ce7ebe9ea5ab23a81bfab75470337e66e235b881ca40ac8932",
        binarySha256 = "9c5954b50520b68d7d181804965b554f09add95cc8fb0db6a7750111a1296b60",
    )

}
