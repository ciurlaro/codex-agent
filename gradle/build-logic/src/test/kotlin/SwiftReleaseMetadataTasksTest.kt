import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwiftReleaseMetadataTasksTest {
    @Test
    fun `public Swift resolution uses clean isolated Xcode package state`() {
        val arguments = publicSwiftResolutionArguments(File("/derived"), File("/packages"))
        assertTrue("-disablePackageRepositoryCache" in arguments)
        assertTrue("generic/platform=iOS Simulator" in arguments)
        assertTrue("CODE_SIGNING_ALLOWED=NO" in arguments)
        assertEquals(listOf("clean", "build"), arguments.takeLast(2))
    }
}
