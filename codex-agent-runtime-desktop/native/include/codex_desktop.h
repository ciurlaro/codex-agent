#ifndef CODEX_DESKTOP_H
#define CODEX_DESKTOP_H

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef intptr_t codex_handle;

typedef struct codex_process {
    codex_handle stdin_write;
    codex_handle stdout_read;
    codex_handle stderr_read;
    codex_handle process;
    codex_handle job;
} codex_process;

static inline const char *codex_getenv(const char *name) {
    return getenv(name);
}

static inline void codex_set_error(char *buffer, size_t capacity, const char *message) {
    if (capacity == 0) return;
    snprintf(buffer, capacity, "%s", message);
    buffer[capacity - 1] = '\0';
}

#ifdef _WIN32
#include "codex_desktop_windows.h"
#else
#include "codex_desktop_posix.h"
#endif

#endif
