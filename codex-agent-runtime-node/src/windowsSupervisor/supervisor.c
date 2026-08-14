#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#define UNICODE
#define _UNICODE
#define _WIN32_WINNT 0x0A00
#define WINVER 0x0A00

#include <windows.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#ifndef PROC_THREAD_ATTRIBUTE_JOB_LIST
#error "Windows 10 PROC_THREAD_ATTRIBUTE_JOB_LIST support is required"
#endif

enum SupervisorExitCode {
    SUPERVISOR_USAGE_ERROR = 64,
    SUPERVISOR_PATH_ERROR = 65,
    SUPERVISOR_WINDOWS_ERROR = 70
};

static int report_windows_error(const wchar_t *stage, DWORD error) {
    fwprintf(stderr, L"codex-agent Windows supervisor failed at %ls (Win32 %lu)\n", stage, error);
    return SUPERVISOR_WINDOWS_ERROR;
}

static int is_exact_absolute_regular_file(const wchar_t *path) {
    DWORD required;
    DWORD written;
    DWORD attributes;
    wchar_t *absolute;
    int matches;

    if (path == NULL || path[0] == L'\0' || wcschr(path, L'\"') != NULL) {
        return 0;
    }
    required = GetFullPathNameW(path, 0, NULL, NULL);
    if (required == 0 || required >= 32767) {
        return 0;
    }
    absolute = (wchar_t *)calloc((size_t)required, sizeof(wchar_t));
    if (absolute == NULL) {
        return 0;
    }
    written = GetFullPathNameW(path, required, absolute, NULL);
    matches = written > 0 && written < required && _wcsicmp(path, absolute) == 0;
    free(absolute);
    if (!matches) {
        return 0;
    }
    attributes = GetFileAttributesW(path);
    return attributes != INVALID_FILE_ATTRIBUTES &&
        (attributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) == 0;
}

static wchar_t *child_command_line(const wchar_t *path) {
    size_t length = wcslen(path);
    wchar_t *command;

    if (length > 32764) {
        return NULL;
    }
    command = (wchar_t *)calloc(length + 3, sizeof(wchar_t));
    if (command == NULL) {
        return NULL;
    }
    command[0] = L'\"';
    memcpy(command + 1, path, length * sizeof(wchar_t));
    command[length + 1] = L'\"';
    return command;
}

int wmain(int argc, wchar_t **argv) {
    HANDLE job = NULL;
    HANDLE stdio_handles[3];
    HANDLE job_handles[1];
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits;
    SIZE_T attribute_bytes = 0;
    LPPROC_THREAD_ATTRIBUTE_LIST attributes = NULL;
    STARTUPINFOEXW startup;
    PROCESS_INFORMATION process;
    wchar_t *command_line = NULL;
    DWORD flags = CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT;
    DWORD child_exit = SUPERVISOR_WINDOWS_ERROR;
    DWORD error = ERROR_SUCCESS;
    const wchar_t *stage = L"validation";
    int attributes_initialized = 0;
    int child_created = 0;

    ZeroMemory(&startup, sizeof(startup));
    ZeroMemory(&process, sizeof(process));
    ZeroMemory(&limits, sizeof(limits));

    if (argc != 2) {
        fwprintf(stderr, L"usage: codex-agent-node-windows-supervisor.exe <absolute-app-server.exe>\n");
        return SUPERVISOR_USAGE_ERROR;
    }
    if (!is_exact_absolute_regular_file(argv[1])) {
        fwprintf(stderr, L"codex-agent Windows supervisor rejected the App Server path\n");
        return SUPERVISOR_PATH_ERROR;
    }
    command_line = child_command_line(argv[1]);
    if (command_line == NULL) {
        return report_windows_error(L"command-line allocation", ERROR_NOT_ENOUGH_MEMORY);
    }

    stdio_handles[0] = GetStdHandle(STD_INPUT_HANDLE);
    stdio_handles[1] = GetStdHandle(STD_OUTPUT_HANDLE);
    stdio_handles[2] = GetStdHandle(STD_ERROR_HANDLE);
    for (size_t index = 0; index < 3; ++index) {
        if (stdio_handles[index] == NULL || stdio_handles[index] == INVALID_HANDLE_VALUE ||
            !SetHandleInformation(stdio_handles[index], HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT)) {
            error = GetLastError();
            stage = L"standard-handle inheritance";
            goto cleanup;
        }
    }

    job = CreateJobObjectW(NULL, NULL);
    if (job == NULL) {
        error = GetLastError();
        stage = L"job creation";
        goto cleanup;
    }
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits))) {
        error = GetLastError();
        stage = L"job policy";
        goto cleanup;
    }

    InitializeProcThreadAttributeList(NULL, 2, 0, &attribute_bytes);
    if (attribute_bytes == 0 || GetLastError() != ERROR_INSUFFICIENT_BUFFER) {
        error = GetLastError();
        stage = L"attribute sizing";
        goto cleanup;
    }
    attributes = (LPPROC_THREAD_ATTRIBUTE_LIST)HeapAlloc(GetProcessHeap(), 0, attribute_bytes);
    if (attributes == NULL) {
        error = ERROR_NOT_ENOUGH_MEMORY;
        stage = L"attribute allocation";
        goto cleanup;
    }
    if (!InitializeProcThreadAttributeList(attributes, 2, 0, &attribute_bytes)) {
        error = GetLastError();
        stage = L"attribute initialization";
        goto cleanup;
    }
    attributes_initialized = 1;
    if (!UpdateProcThreadAttribute(
            attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
            stdio_handles, sizeof(stdio_handles), NULL, NULL)) {
        error = GetLastError();
        stage = L"standard-handle restriction";
        goto cleanup;
    }
    job_handles[0] = job;
    if (!UpdateProcThreadAttribute(
            attributes, 0, PROC_THREAD_ATTRIBUTE_JOB_LIST,
            job_handles, sizeof(job_handles), NULL, NULL)) {
        error = GetLastError();
        stage = L"atomic job assignment";
        goto cleanup;
    }

    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = stdio_handles[0];
    startup.StartupInfo.hStdOutput = stdio_handles[1];
    startup.StartupInfo.hStdError = stdio_handles[2];
    startup.lpAttributeList = attributes;
    if (!CreateProcessW(
            argv[1], command_line, NULL, NULL, TRUE, flags,
            NULL, NULL, &startup.StartupInfo, &process)) {
        error = GetLastError();
        stage = L"suspended child creation";
        goto cleanup;
    }
    child_created = 1;
    if (ResumeThread(process.hThread) == (DWORD)-1) {
        error = GetLastError();
        stage = L"child resume";
        goto cleanup;
    }
    CloseHandle(process.hThread);
    process.hThread = NULL;
    if (WaitForSingleObject(process.hProcess, INFINITE) != WAIT_OBJECT_0) {
        error = GetLastError();
        stage = L"child wait";
        goto cleanup;
    }
    if (!GetExitCodeProcess(process.hProcess, &child_exit)) {
        error = GetLastError();
        stage = L"child exit status";
        goto cleanup;
    }

cleanup:
    if (attributes != NULL) {
        if (attributes_initialized) {
            DeleteProcThreadAttributeList(attributes);
        }
        HeapFree(GetProcessHeap(), 0, attributes);
    }
    free(command_line);
    if (job != NULL) {
        CloseHandle(job);
    }
    if (child_created && error != ERROR_SUCCESS && process.hProcess != NULL) {
        WaitForSingleObject(process.hProcess, 5000);
    }
    if (process.hThread != NULL) {
        CloseHandle(process.hThread);
    }
    if (process.hProcess != NULL) {
        CloseHandle(process.hProcess);
    }
    if (error != ERROR_SUCCESS) {
        return report_windows_error(stage, error);
    }
    return (int)child_exit;
}
