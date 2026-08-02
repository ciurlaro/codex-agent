package io.github.ciurlaro.codexmobile.appserver.runtime

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeLogPrivacyGuardTest {
    @Test
    fun deletesExistingLogsRejectsLaterInsertsAndEnablesSecureDeletion() {
        BundledSQLiteDriver().open(":memory:").use { database ->
            database.execSQL("CREATE TABLE logs (message TEXT)")
            database.execSQL("INSERT INTO logs VALUES ('existing sensitive log')")

            installRuntimeLogPrivacyGuard(database)

            assertEquals(0, database.longQuery("SELECT COUNT(*) FROM logs"))
            database.execSQL("INSERT INTO logs VALUES ('later sensitive log')")
            assertEquals(0, database.longQuery("SELECT COUNT(*) FROM logs"))
            assertEquals(1, database.longQuery("PRAGMA secure_delete"))
            assertTrue(
                database.longQuery(
                    "SELECT COUNT(*) FROM sqlite_schema WHERE type = 'trigger' " +
                        "AND name = 'codex_mobile_drop_runtime_logs'",
                ) > 0,
            )
        }
    }

    @Test
    fun removesSensitiveBytesFromFileBackedDatabaseAndWal() {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "codex-runtime-log-${Random.nextLong()}"
        val databasePath = directory / "logs.sqlite"
        FileSystem.SYSTEM.createDirectories(directory)
        val sentinel = "forensic-runtime-log-sentinel"
        try {
            BundledSQLiteDriver().open(databasePath.toString()).use { database ->
                database.prepare("PRAGMA journal_mode=WAL").use { check(it.step()) }
                database.execSQL("CREATE TABLE logs (message TEXT)")
                database.execSQL("INSERT INTO logs VALUES ('$sentinel')")

                installRuntimeLogPrivacyGuard(database)

                assertEquals(0, database.longQuery("SELECT COUNT(*) FROM logs"))
                database.execSQL("INSERT INTO logs VALUES ('later sensitive log')")
                assertEquals(0, database.longQuery("SELECT COUNT(*) FROM logs"))
            }

            val sentinelBytes = sentinel.encodeToByteArray()
            listOf(databasePath, "$databasePath-wal".toPath(), "$databasePath-shm".toPath())
                .filter { FileSystem.SYSTEM.exists(it) }
                .forEach { path ->
                    val bytes = FileSystem.SYSTEM.source(path).buffer().use { it.readByteArray() }
                    assertFalse(bytes.contains(sentinelBytes), "Sensitive log remained in $path")
                }
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
        }
    }
}

private fun SQLiteConnection.longQuery(sql: String): Long = prepare(sql).use { statement ->
    check(statement.step())
    statement.getLong(0)
}

private fun ByteArray.contains(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    for (start in 0..size - needle.size) {
        if (needle.indices.all { offset -> this[start + offset] == needle[offset] }) return true
    }
    return false
}
