#ifndef CODEX_AGENT_IOS_H
#define CODEX_AGENT_IOS_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct CodexAgentIosRuntime CodexAgentIosRuntime;

typedef struct CodexAgentIosBuffer {
    uint8_t *data;
    size_t length;
} CodexAgentIosBuffer;

/*
 * A successful start transfers one opaque runtime to the caller. Call shutdown,
 * then destroy it exactly once. A runtime supports one concurrent receiver.
 */

enum CodexAgentIosEventKind {
    CODEX_AGENT_IOS_EVENT_MESSAGE = 1,
    CODEX_AGENT_IOS_EVENT_IO_FAILURE = 2,
    CODEX_AGENT_IOS_EVENT_END_OF_FILE = 3,
    CODEX_AGENT_IOS_EVENT_EXITED = 4
};

int32_t codex_agent_ios_runtime_start(
    const uint8_t *configuration,
    size_t configuration_length,
    CodexAgentIosRuntime **runtime,
    CodexAgentIosBuffer *error
);

int32_t codex_agent_ios_runtime_send(
    CodexAgentIosRuntime *runtime,
    const uint8_t *message,
    size_t message_length,
    CodexAgentIosBuffer *error
);

/* Blocks until an event arrives. Returns 0, 1 after closure, or a negative failure. */
int32_t codex_agent_ios_runtime_receive(
    CodexAgentIosRuntime *runtime,
    int32_t *kind,
    CodexAgentIosBuffer *payload,
    CodexAgentIosBuffer *error
);

int32_t codex_agent_ios_runtime_shutdown(
    CodexAgentIosRuntime *runtime,
    CodexAgentIosBuffer *error
);

void codex_agent_ios_runtime_destroy(CodexAgentIosRuntime *runtime);

int32_t codex_agent_ios_workspace_execute(
    const uint8_t *configuration,
    size_t configuration_length,
    const uint8_t *tool,
    size_t tool_length,
    const uint8_t *arguments,
    size_t arguments_length,
    CodexAgentIosBuffer *result,
    CodexAgentIosBuffer *error
);

/* Frees any non-empty buffer returned by this API and clears its fields. */
void codex_agent_ios_buffer_free(CodexAgentIosBuffer *buffer);

#ifdef __cplusplus
}
#endif

#endif
