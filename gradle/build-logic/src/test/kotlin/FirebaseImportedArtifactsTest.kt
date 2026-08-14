import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FirebaseImportedArtifactsTest {
    @Test
    fun `exact-main Android artifacts are accepted only as one complete set`() {
        val app = File("app.apk")
        val test = File("test.apk")
        val aar = File("runtime.aar")

        assertNull(firebaseImportedArtifacts(null, null, null))
        assertEquals(FirebaseImportedArtifacts(app, test, aar), firebaseImportedArtifacts(app, test, aar))
        assertFailsWith<IllegalStateException> { firebaseImportedArtifacts(app, test, null) }
    }
}
