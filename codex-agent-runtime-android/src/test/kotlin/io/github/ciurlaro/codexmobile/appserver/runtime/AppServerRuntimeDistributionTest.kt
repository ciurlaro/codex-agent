package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.test.Test
import kotlin.test.assertFailsWith

class AppServerRuntimeDistributionTest {
    private val distribution = CodexMobileAppServerRuntime.DISTRIBUTION
    private val environment = RuntimeEnvironment(RuntimeKernel.LINUX, RuntimeArchitecture.AARCH64, true)

    @Test
    fun acceptsOnlyTheExactProtocolIdentityAndExecutableEnvironment() {
        distribution.requireCompatible(
            distribution.appServerVersion,
            distribution.upstreamRevision,
            distribution.schemaSha256,
            environment,
        )
        assertFailsWith<IllegalArgumentException> {
            distribution.requireCompatible("0.144.7", distribution.upstreamRevision, distribution.schemaSha256, environment)
        }
        assertFailsWith<IllegalArgumentException> {
            distribution.requireCompatible(
                distribution.appServerVersion,
                distribution.upstreamRevision,
                distribution.schemaSha256,
                environment.copy(supportsStaticElf = false),
            )
        }
    }
}
