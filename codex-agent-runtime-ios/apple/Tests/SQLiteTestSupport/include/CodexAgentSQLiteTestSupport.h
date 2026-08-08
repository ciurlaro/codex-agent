#ifndef CODEX_AGENT_SQLITE_TEST_SUPPORT_H
#define CODEX_AGENT_SQLITE_TEST_SUPPORT_H

#include <stddef.h>

int codex_agent_run_sqlite_tests(const char *path, char *error, size_t error_capacity);

#endif
