import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryVerificationTasksTest {
    @Test
    fun `ordinary Android verification uses the complete release variant gates`() {
        assertTrue(":codex-agent-runtime-android:testDebugUnitTest" in repositoryVerificationTaskPaths)
        assertTrue(":codex-agent-runtime-android:lintRelease" in repositoryVerificationTaskPaths)
        assertTrue(":codex-agent-runtime-android:assembleRelease" in repositoryVerificationTaskPaths)
        assertFalse(":codex-agent-runtime-android:testReleaseUnitTest" in repositoryVerificationTaskPaths)
        assertFalse(":codex-agent-runtime-android:lint" in repositoryVerificationTaskPaths)
    }
}
