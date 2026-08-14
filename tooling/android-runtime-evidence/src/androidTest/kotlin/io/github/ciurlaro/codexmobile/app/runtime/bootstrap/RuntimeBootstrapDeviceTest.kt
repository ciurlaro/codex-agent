package io.github.ciurlaro.codexmobile.app.runtime.bootstrap

import android.content.Context
import android.os.Build
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexJsonLine
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeEvent
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBootstrapDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun missingNonExecutableAndCorruptOverridesFailClosed() {
        assertDeviceContract()
        clearRuntimeState()
        try {
            val missing = File(context.cacheDir, "missing-codex-runtime").also(File::delete)
            assertStartFails(missing)

            val nonExecutable = File.createTempFile("codex-runtime-", ".bin", context.cacheDir)
            try {
                nonExecutable.writeText("not executable")
                assertTrue(nonExecutable.setExecutable(false, false))
                assertFalse(nonExecutable.canExecute())
                assertStartFails(nonExecutable)
            } finally {
                nonExecutable.delete()
            }

            val corrupt = File.createTempFile("codex-runtime-", ".bin", context.cacheDir)
            try {
                corrupt.writeText("not an ELF executable")
                assertTrue(corrupt.setExecutable(true, true))
                assertStartFails(corrupt)
            } finally {
                corrupt.delete()
            }
        } finally {
            clearRuntimeState()
        }
    }

    @Test
    fun successfulRuntimeInstallsCertificatePrivacyAndCleanupPolicies(): Unit = runBlocking {
        assertDeviceContract()
        clearRuntimeState()
        val codexHome = File(context.noBackupFilesDir, "codex")
        val certificateBundle = File(codexHome, "system-ca.pem")
        startAndInitializeRuntime {
            assertTrue(certificateBundle.length() > 0)
        }
        assertEventuallyDeleted(certificateBundle)
        assertEventuallyDeleted(File(context.noBackupFilesDir, "codex-app-server.stdout"))

        val databasePath = File(codexHome, "logs_2.sqlite").absolutePath
        AndroidSQLiteDriver().open(databasePath).use { database ->
            database.execSQL("DROP TRIGGER IF EXISTS codex_mobile_drop_runtime_logs")
            database.execSQL(
                "INSERT INTO logs (ts, ts_nanos, level, target, feedback_log_body) " +
                    "VALUES (1, 0, 'INFO', 'privacy_test', 'existing sensitive log')",
            )
            assertEquals(1, database.longQuery("SELECT COUNT(*) FROM logs"))
        }

        startAndInitializeRuntime()

        val database = AndroidSQLiteDriver().open(databasePath)
        try {
            assertEquals(0, database.longQuery("SELECT COUNT(*) FROM logs"))
            database.execSQL(
                "INSERT INTO logs (ts, ts_nanos, level, target, feedback_log_body) " +
                    "VALUES (2, 0, 'INFO', 'privacy_test', 'later sensitive log')",
            )
            assertEquals(0, database.longQuery("SELECT COUNT(*) FROM logs"))
            assertEquals(1, database.longQuery("PRAGMA secure_delete"))
            assertEquals(
                1,
                database.longQuery(
                    "SELECT COUNT(*) FROM sqlite_schema WHERE type = 'trigger' " +
                        "AND name = 'codex_mobile_drop_runtime_logs'",
                ),
            )
        } finally {
            database.close()
        }
        val sentinel = "existing sensitive log".toByteArray()
        listOf(File(databasePath), File("$databasePath-wal"), File("$databasePath-shm"))
            .filter(File::exists)
            .forEach { file ->
                assertFalse(
                    "${file.name} retained sensitive runtime-log bytes",
                    file.readBytes().contains(sentinel),
                )
            }
    }

    private suspend fun startAndInitializeRuntime(whileRunning: () -> Unit = {}) = coroutineScope {
        val runtime = AndroidCodexRuntimeFactory(context).create()
        try {
            runtime.start()
            val initialized = async {
                withTimeout(120_000) {
                    runtime.events.first { event ->
                        event is CodexRuntimeEvent.Received && "\"id\":1" in event.line.value
                    }
                }
            }
            runtime.send(
                CodexJsonLine(
                    """{"id":1,"method":"initialize","params":{"clientInfo":{"name":"runtime_policy_test","title":"Runtime Policy Test","version":"1"}}}""",
                ),
            )
            initialized.await()
            whileRunning()
        } finally {
            runtime.close()
        }
    }

    private fun assertStartFails(executable: File) {
        val runtime = createRuntimeWithOverride(executable)
        val failure = try {
            runCatching { runBlocking { runtime.start() } }.exceptionOrNull()
        } finally {
            runtime.close()
        }
        assertTrue("Expected runtime startup failure for ${executable.name}", failure != null)
    }

    private fun createRuntimeWithOverride(executable: File): CodexRuntime {
        val type = Class.forName(
            "io.github.ciurlaro.codexmobile.app.runtime.bootstrap.AndroidRuntimeBootstrap",
        )
        val constructor = type.declaredConstructors.single { candidate ->
            candidate.parameterTypes.contentEquals(arrayOf(Context::class.java, File::class.java))
        }.apply { isAccessible = true }
        val bootstrap = constructor.newInstance(context, executable)
        val create = type.getDeclaredMethod("create").apply { isAccessible = true }
        return try {
            create.invoke(bootstrap) as CodexRuntime
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun assertDeviceContract() {
        assertEquals(35, Build.VERSION.SDK_INT)
        assertEquals("arm64-v8a", Build.SUPPORTED_ABIS.firstOrNull())
    }

    private fun assertEventuallyDeleted(file: File) {
        repeat(100) {
            if (!file.exists()) return
            Thread.sleep(20)
        }
        assertFalse("${file.name} was not deleted", file.exists())
    }

    private fun clearRuntimeState() {
        File(context.noBackupFilesDir, "codex").deleteRecursively()
        File(context.noBackupFilesDir, "codex-app-server.stdout").delete()
        File(context.filesDir, "home").deleteRecursively()
    }

    private fun androidx.sqlite.SQLiteConnection.longQuery(sql: String): Long = prepare(sql).use { statement ->
        assertTrue(statement.step())
        statement.getLong(0)
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..size - needle.size) {
            if (needle.indices.all { offset -> this[start + offset] == needle[offset] }) return true
        }
        return false
    }
}
