#ifndef CODEX_DESKTOP_WINDOWS_H
#define CODEX_DESKTOP_WINDOWS_H

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

static inline void codex_set_windows_error(char *buffer, size_t capacity, const char *operation) {
    if (capacity == 0) return;
    snprintf(buffer, capacity, "%s failed with Windows error %lu", operation, GetLastError());
    buffer[capacity - 1] = '\0';
}

static inline wchar_t *codex_utf8_to_wide(const char *value) {
    int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, NULL, 0);
    if (length <= 0) return NULL;
    wchar_t *wide = (wchar_t *)malloc((size_t)length * sizeof(wchar_t));
    if (wide == NULL) return NULL;
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, wide, length) == 0) {
        free(wide);
        return NULL;
    }
    return wide;
}

static inline int codex_process_start(
    const char *executable,
    const char *argument,
    const char *working_directory,
    codex_process *output,
    char *error,
    size_t error_capacity
) {
    SECURITY_ATTRIBUTES security = {sizeof(SECURITY_ATTRIBUTES), NULL, TRUE};
    HANDLE stdin_read = NULL, stdin_write = NULL;
    HANDLE stdout_read = NULL, stdout_write = NULL;
    HANDLE stderr_read = NULL, stderr_write = NULL;
    wchar_t *wide_executable = NULL, *wide_argument = NULL, *wide_directory = NULL, *command = NULL;
    PROCESS_INFORMATION process_info;
    STARTUPINFOW startup_info;
    ZeroMemory(&process_info, sizeof(process_info));
    ZeroMemory(&startup_info, sizeof(startup_info));
    startup_info.cb = sizeof(startup_info);

    wide_executable = codex_utf8_to_wide(executable);
    wide_argument = codex_utf8_to_wide(argument);
    wide_directory = codex_utf8_to_wide(working_directory);
    if (wide_executable == NULL || wide_argument == NULL || wide_directory == NULL) {
        codex_set_error(error, error_capacity, "Desktop runtime paths must be valid UTF-8");
        goto fail;
    }
    size_t command_length = wcslen(wide_executable) + wcslen(wide_argument) + 6;
    command = (wchar_t *)malloc(command_length * sizeof(wchar_t));
    if (command == NULL) {
        codex_set_error(error, error_capacity, "Unable to allocate the app-server command line");
        goto fail;
    }
    swprintf(command, command_length, L"\"%ls\" \"%ls\"", wide_executable, wide_argument);

    if (!CreatePipe(&stdin_read, &stdin_write, &security, 0) ||
        !CreatePipe(&stdout_read, &stdout_write, &security, 0) ||
        !CreatePipe(&stderr_read, &stderr_write, &security, 0)) {
        codex_set_windows_error(error, error_capacity, "CreatePipe");
        goto fail;
    }
    if (!SetHandleInformation(stdin_write, HANDLE_FLAG_INHERIT, 0) ||
        !SetHandleInformation(stdout_read, HANDLE_FLAG_INHERIT, 0) ||
        !SetHandleInformation(stderr_read, HANDLE_FLAG_INHERIT, 0)) {
        codex_set_windows_error(error, error_capacity, "SetHandleInformation");
        goto fail;
    }

    startup_info.dwFlags = STARTF_USESTDHANDLES;
    startup_info.hStdInput = stdin_read;
    startup_info.hStdOutput = stdout_write;
    startup_info.hStdError = stderr_write;
    if (!CreateProcessW(
            wide_executable,
            command,
            NULL,
            NULL,
            TRUE,
            CREATE_NO_WINDOW,
            NULL,
            wide_directory,
            &startup_info,
            &process_info
        )) {
        codex_set_windows_error(error, error_capacity, "CreateProcessW");
        goto fail;
    }
    CloseHandle(process_info.hThread);
    CloseHandle(stdin_read);
    CloseHandle(stdout_write);
    CloseHandle(stderr_write);
    free(wide_executable);
    free(wide_argument);
    free(wide_directory);
    free(command);
    output->stdin_write = (codex_handle)stdin_write;
    output->stdout_read = (codex_handle)stdout_read;
    output->stderr_read = (codex_handle)stderr_read;
    output->process = (codex_handle)process_info.hProcess;
    output->job = 0;
    return 0;

fail:
    if (process_info.hThread != NULL) CloseHandle(process_info.hThread);
    if (process_info.hProcess != NULL) CloseHandle(process_info.hProcess);
    if (stdin_read != NULL) CloseHandle(stdin_read);
    if (stdin_write != NULL) CloseHandle(stdin_write);
    if (stdout_read != NULL) CloseHandle(stdout_read);
    if (stdout_write != NULL) CloseHandle(stdout_write);
    if (stderr_read != NULL) CloseHandle(stderr_read);
    if (stderr_write != NULL) CloseHandle(stderr_write);
    free(wide_executable);
    free(wide_argument);
    free(wide_directory);
    free(command);
    return -1;
}

static inline ptrdiff_t codex_process_read(codex_handle raw_handle, void *buffer, size_t capacity) {
    DWORD read_count = 0;
    if (ReadFile((HANDLE)raw_handle, buffer, (DWORD)capacity, &read_count, NULL)) return (ptrdiff_t)read_count;
    return GetLastError() == ERROR_BROKEN_PIPE ? 0 : -1;
}

static inline int codex_process_write(codex_handle raw_handle, const void *bytes, size_t count) {
    const unsigned char *cursor = (const unsigned char *)bytes;
    while (count > 0) {
        DWORD written = 0;
        DWORD chunk = count > 0xffffffffu ? 0xffffffffu : (DWORD)count;
        if (!WriteFile((HANDLE)raw_handle, cursor, chunk, &written, NULL) || written == 0) return -1;
        cursor += written;
        count -= written;
    }
    return 0;
}

static inline void codex_process_close(codex_handle raw_handle) {
    if (raw_handle != 0 && raw_handle != -1) CloseHandle((HANDLE)raw_handle);
}

static inline int codex_process_wait(codex_handle raw_process, int *exit_code) {
    HANDLE process = (HANDLE)raw_process;
    if (WaitForSingleObject(process, INFINITE) != WAIT_OBJECT_0) return -1;
    DWORD code = 0;
    if (!GetExitCodeProcess(process, &code)) return -1;
    *exit_code = (int)code;
    return 0;
}

static inline void codex_process_terminate(codex_handle raw_process, codex_handle raw_job) {
    (void)raw_job;
    HANDLE process = (HANDLE)raw_process;
    if (WaitForSingleObject(process, 2000) == WAIT_TIMEOUT) {
        TerminateProcess(process, 1);
        WaitForSingleObject(process, INFINITE);
    }
}

static inline void codex_process_release(codex_handle raw_process, codex_handle raw_job) {
    if (raw_process != 0) CloseHandle((HANDLE)raw_process);
    (void)raw_job;
}

#endif
