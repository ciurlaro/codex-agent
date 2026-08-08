#include "CodexAgentSQLiteTestSupport.h"

#include <pthread.h>
#include <sqlite3.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

typedef struct {
    sqlite3 *database;
    pthread_mutex_t mutex;
    pthread_cond_t condition;
    int done;
    int result;
    char message[256];
} WriterContext;

static int fail(char *error, size_t capacity, const char *operation, sqlite3 *database) {
    snprintf(
        error,
        capacity,
        "%s: %s",
        operation,
        database == NULL ? "SQLite database is unavailable" : sqlite3_errmsg(database)
    );
    return 1;
}

static int execute(sqlite3 *database, const char *sql, char *error, size_t capacity) {
    char *sqlite_error = NULL;
    int result = sqlite3_exec(database, sql, NULL, NULL, &sqlite_error);
    if (result == SQLITE_OK) {
        return 0;
    }
    snprintf(error, capacity, "%s: %s", sql, sqlite_error == NULL ? sqlite3_errmsg(database) : sqlite_error);
    sqlite3_free(sqlite_error);
    return 1;
}

static int scalar_text(
    sqlite3 *database,
    const char *sql,
    const char *expected,
    char *error,
    size_t capacity
) {
    sqlite3_stmt *statement = NULL;
    if (sqlite3_prepare_v2(database, sql, -1, &statement, NULL) != SQLITE_OK) {
        return fail(error, capacity, sql, database);
    }
    int result = sqlite3_step(statement);
    const unsigned char *value = result == SQLITE_ROW ? sqlite3_column_text(statement, 0) : NULL;
    int matches = value != NULL && strcmp((const char *)value, expected) == 0;
    sqlite3_finalize(statement);
    if (!matches) {
        snprintf(error, capacity, "%s did not return %s", sql, expected);
        return 1;
    }
    return 0;
}

static void *write_concurrently(void *opaque) {
    WriterContext *context = opaque;
    char *sqlite_error = NULL;
    context->result = sqlite3_exec(
        context->database,
        "INSERT INTO durability(value) VALUES ('concurrent-writer')",
        NULL,
        NULL,
        &sqlite_error
    );
    if (sqlite_error != NULL) {
        snprintf(context->message, sizeof(context->message), "%s", sqlite_error);
        sqlite3_free(sqlite_error);
    }
    pthread_mutex_lock(&context->mutex);
    context->done = 1;
    pthread_cond_signal(&context->condition);
    pthread_mutex_unlock(&context->mutex);
    return NULL;
}

int codex_agent_run_sqlite_tests(const char *path, char *error, size_t error_capacity) {
    sqlite3 *writer = NULL;
    sqlite3 *reader = NULL;
    sqlite3 *reopened = NULL;
    pthread_t writer_thread;
    int writer_thread_started = 0;
    int result = 1;
    WriterContext context = {0};

    unlink(path);
    char wal_path[4096];
    char shm_path[4096];
    snprintf(wal_path, sizeof(wal_path), "%s-wal", path);
    snprintf(shm_path, sizeof(shm_path), "%s-shm", path);
    unlink(wal_path);
    unlink(shm_path);

    if (sqlite3_open_v2(path, &writer, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, NULL) != SQLITE_OK) {
        fail(error, error_capacity, "open writer", writer);
        goto cleanup;
    }
    sqlite3_busy_timeout(writer, 5000);
    if (scalar_text(writer, "PRAGMA journal_mode=WAL", "wal", error, error_capacity)) goto cleanup;
    if (execute(
            writer,
            "CREATE TABLE durability(id INTEGER PRIMARY KEY, value TEXT NOT NULL);"
            "CREATE TABLE auth_state(id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL);"
            "INSERT INTO durability(value) VALUES ('seed');",
            error,
            error_capacity
        )) goto cleanup;
    if (sqlite3_open_v2(path, &reader, SQLITE_OPEN_READWRITE, NULL) != SQLITE_OK) {
        fail(error, error_capacity, "open reader", reader);
        goto cleanup;
    }
    sqlite3_busy_timeout(reader, 5000);
    if (execute(reader, "BEGIN; SELECT * FROM durability;", error, error_capacity)) goto cleanup;

    context.database = writer;
    pthread_mutex_init(&context.mutex, NULL);
    pthread_cond_init(&context.condition, NULL);
    if (pthread_create(&writer_thread, NULL, write_concurrently, &context) != 0) {
        snprintf(error, error_capacity, "pthread_create failed");
        goto cleanup;
    }
    writer_thread_started = 1;
    pthread_mutex_lock(&context.mutex);
    while (!context.done) pthread_cond_wait(&context.condition, &context.mutex);
    pthread_mutex_unlock(&context.mutex);
    if (context.result != SQLITE_OK) {
        snprintf(error, error_capacity, "concurrent WAL writer failed: %s", context.message);
        goto cleanup;
    }
    if (execute(reader, "COMMIT", error, error_capacity)) goto cleanup;
    pthread_join(writer_thread, NULL);
    writer_thread_started = 0;

    if (execute(writer, "BEGIN IMMEDIATE; INSERT INTO durability(value) VALUES ('committed'); COMMIT;", error, error_capacity)) {
        goto cleanup;
    }
    if (execute(writer, "BEGIN IMMEDIATE; INSERT INTO durability(value) VALUES ('rolled-back'); ROLLBACK;", error, error_capacity)) {
        goto cleanup;
    }
    for (int index = 0; index < 256; index++) {
        char sql[192];
        snprintf(
            sql,
            sizeof(sql),
            "INSERT INTO auth_state(id,value) VALUES(1,'generation-%d') "
            "ON CONFLICT(id) DO UPDATE SET value=excluded.value",
            index
        );
        if (execute(writer, sql, error, error_capacity)) goto cleanup;
    }

    sqlite3_close(reader);
    reader = NULL;
    sqlite3_close(writer);
    writer = NULL;
    if (sqlite3_open_v2(path, &reopened, SQLITE_OPEN_READWRITE, NULL) != SQLITE_OK) {
        fail(error, error_capacity, "reopen after runtime restart", reopened);
        goto cleanup;
    }
    if (scalar_text(reopened, "PRAGMA integrity_check", "ok", error, error_capacity)) goto cleanup;
    if (scalar_text(
            reopened,
            "SELECT CAST(COUNT(*) AS TEXT) FROM durability WHERE value IN ('seed','concurrent-writer','committed')",
            "3",
            error,
            error_capacity
        )) goto cleanup;
    if (scalar_text(
            reopened,
            "SELECT CAST(COUNT(*) AS TEXT) FROM durability WHERE value='rolled-back'",
            "0",
            error,
            error_capacity
        )) goto cleanup;
    if (scalar_text(reopened, "SELECT value FROM auth_state WHERE id=1", "generation-255", error, error_capacity)) {
        goto cleanup;
    }
    result = 0;

cleanup:
    if (writer_thread_started) pthread_join(writer_thread, NULL);
    if (context.database != NULL) {
        pthread_cond_destroy(&context.condition);
        pthread_mutex_destroy(&context.mutex);
    }
    if (reader != NULL) sqlite3_close(reader);
    if (writer != NULL) sqlite3_close(writer);
    if (reopened != NULL) sqlite3_close(reopened);
    unlink(path);
    unlink(wal_path);
    unlink(shm_path);
    return result;
}
