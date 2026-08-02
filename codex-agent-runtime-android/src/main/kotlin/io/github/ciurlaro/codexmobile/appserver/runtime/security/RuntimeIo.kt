package io.github.ciurlaro.codexmobile.appserver.runtime

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

const val MAX_RECEIVED_MESSAGE_BYTES = 32 * 1024 * 1024

internal fun Throwable.visibleMessage(): String =
    message?.take(500)?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: "Codex failure"

fun installRuntimeLogPrivacyGuard(database: SQLiteConnection) {
    database.execSQL("PRAGMA secure_delete=ON")
    database.execSQL("BEGIN IMMEDIATE")
    try {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS logs (
                ts INTEGER NOT NULL,
                ts_nanos INTEGER NOT NULL,
                level TEXT NOT NULL,
                target TEXT NOT NULL,
                feedback_log_body TEXT NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL("DELETE FROM logs")
        database.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS codex_mobile_drop_runtime_logs
            BEFORE INSERT ON logs
            BEGIN
                SELECT RAISE(IGNORE);
            END
            """.trimIndent(),
        )
        database.execSQL("COMMIT")
    } catch (error: Throwable) {
        runCatching { database.execSQL("ROLLBACK") }
        throw error
    }
    checkpointAndTruncate(database)
    database.execSQL("VACUUM")
    checkpointAndTruncate(database)
}

private fun checkpointAndTruncate(database: SQLiteConnection) {
    database.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { statement ->
        check(statement.step()) { "SQLite checkpoint returned no status" }
        check(statement.getLong(0) == 0L) { "SQLite checkpoint could not acquire the database lock" }
    }
}
