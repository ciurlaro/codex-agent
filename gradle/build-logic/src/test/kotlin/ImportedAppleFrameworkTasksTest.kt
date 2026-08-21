import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImportedAppleFrameworkTasksTest {
    @Test
    fun `Kotlin Native framework platform uses supported-platform plist semantics`() {
        val infoPlist = File("CodexAgent.framework/Info.plist")
        assertEquals(
            listOf(
                "/usr/bin/plutil", "-extract", "CFBundleSupportedPlatforms.0", "raw", "-o", "-",
                infoPlist.absolutePath,
            ),
            importedFrameworkPlatformCommand(infoPlist),
        )
        verifyImportedFrameworkPlatform("iphoneos", "iPhoneOS\n")
        verifyImportedFrameworkPlatform("iphonesimulator", "iPhoneSimulator\n")
        val failure = assertFailsWith<IllegalStateException> {
            verifyImportedFrameworkPlatform("iphoneos", "iPhoneSimulator\n")
        }
        assertEquals(
            "Imported framework platform mismatch: expected=iphoneos actual=iPhoneSimulator",
            failure.message,
        )
    }
}
