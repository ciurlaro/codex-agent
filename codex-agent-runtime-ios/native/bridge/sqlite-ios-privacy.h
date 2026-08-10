#ifndef CODEX_AGENT_SQLITE_IOS_PRIVACY_H
#define CODEX_AGENT_SQLITE_IOS_PRIVACY_H

#ifndef __ASSEMBLER__

#include <string.h>
#include <sys/mount.h>

static inline int codex_agent_sqlite_statfs(
    const char *path,
    struct statfs *buffer
) {
    (void)path;
    memset(buffer, 0, sizeof(*buffer));
    return 0;
}

static inline int codex_agent_sqlite_fstatfs(
    int descriptor,
    struct statfs *buffer
) {
    (void)descriptor;
    memset(buffer, 0, sizeof(*buffer));
    return 0;
}

#define statfs(path, buffer) codex_agent_sqlite_statfs((path), (buffer))
#define fstatfs(descriptor, buffer) codex_agent_sqlite_fstatfs((descriptor), (buffer))

#endif
#endif
