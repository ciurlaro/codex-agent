#ifdef _WIN32

#define WIN32_LEAN_AND_MEAN
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>
#include <windows.h>

static int fail(const wchar_t *operation) {
    fwprintf(stderr, L"%ls failed with Windows error %lu\n", operation, GetLastError());
    return 125;
}

int wmain(int argc, wchar_t **argv) {
    if (argc != 2) {
        fwprintf(stderr, L"usage: codex-process-supervisor <app-server>\n");
        return 125;
    }
    HANDLE job = CreateJobObjectW(NULL, NULL);
    if (job == NULL) return fail(L"CreateJobObjectW");
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits;
    ZeroMemory(&limits, sizeof(limits));
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits))) {
        CloseHandle(job);
        return fail(L"SetInformationJobObject");
    }

    size_t command_size = wcslen(argv[1]) + 3;
    wchar_t *command = (wchar_t *)malloc(command_size * sizeof(wchar_t));
    if (command == NULL) {
        CloseHandle(job);
        return 125;
    }
    swprintf(command, command_size, L"\"%ls\"", argv[1]);
    STARTUPINFOW startup;
    PROCESS_INFORMATION process;
    ZeroMemory(&startup, sizeof(startup));
    ZeroMemory(&process, sizeof(process));
    startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    startup.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    startup.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    if (!CreateProcessW(
            argv[1], command, NULL, NULL, TRUE, CREATE_NO_WINDOW | CREATE_SUSPENDED,
            NULL, NULL, &startup, &process
        )) {
        free(command);
        CloseHandle(job);
        return fail(L"CreateProcessW");
    }
    free(command);
    if (!AssignProcessToJobObject(job, process.hProcess)) {
        TerminateProcess(process.hProcess, 125);
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
        CloseHandle(job);
        return fail(L"AssignProcessToJobObject");
    }
    if (ResumeThread(process.hThread) == (DWORD)-1) {
        TerminateJobObject(job, 125);
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
        CloseHandle(job);
        return fail(L"ResumeThread");
    }
    CloseHandle(process.hThread);
    if (WaitForSingleObject(process.hProcess, INFINITE) != WAIT_OBJECT_0) {
        TerminateJobObject(job, 125);
        CloseHandle(process.hProcess);
        CloseHandle(job);
        return fail(L"WaitForSingleObject");
    }
    DWORD exit_code = 125;
    GetExitCodeProcess(process.hProcess, &exit_code);
    CloseHandle(process.hProcess);
    CloseHandle(job);
    return (int)exit_code;
}

#else

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t shutdown_signal = 0;

static void request_shutdown(int signal_number) {
    shutdown_signal = signal_number;
}

static int child_exit_code(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 125;
}

static int child_group_exists(pid_t child) {
    if (kill(-child, 0) == 0) return 1;
    return errno == EPERM;
}

static void pause_for_child(void) {
    const struct timespec pause = {0, 10000000};
    nanosleep(&pause, NULL);
}

static void terminate_remaining_group(pid_t child) {
    if (!child_group_exists(child)) return;
    kill(-child, SIGTERM);
    for (int attempt = 0; attempt < 200; attempt++) {
        if (!child_group_exists(child)) return;
        pause_for_child();
    }
    kill(-child, SIGKILL);
    for (int attempt = 0; attempt < 100 && child_group_exists(child); attempt++) pause_for_child();
}

static int terminate_child_group(pid_t child) {
    int status = 0;
    pid_t result;
    int exit_code = 128 + shutdown_signal;
    int child_reaped = 0;
    kill(-child, SIGTERM);
    for (int attempt = 0; attempt < 200; attempt++) {
        if (!child_reaped) {
            result = waitpid(child, &status, WNOHANG);
            if (result == child) {
                exit_code = child_exit_code(status);
                child_reaped = 1;
            } else if (result < 0 && errno == ECHILD) {
                child_reaped = 1;
            }
        }
        if (child_reaped && !child_group_exists(child)) return exit_code;
        pause_for_child();
    }
    kill(-child, SIGKILL);
    if (!child_reaped) {
        do result = waitpid(child, &status, 0); while (result < 0 && errno == EINTR);
        if (result == child) exit_code = child_exit_code(status);
    }
    for (int attempt = 0; attempt < 100 && child_group_exists(child); attempt++) pause_for_child();
    return exit_code;
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fputs("usage: codex-process-supervisor <app-server>\n", stderr);
        return 125;
    }
    struct sigaction action;
    action.sa_handler = request_shutdown;
    sigemptyset(&action.sa_mask);
    action.sa_flags = 0;
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGHUP, &action, NULL);

    pid_t child = fork();
    if (child < 0) {
        perror("fork");
        return 125;
    }
    if (child == 0) {
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        setpgid(0, 0);
        execl(argv[1], argv[1], (char *)NULL);
        perror("exec");
        _exit(127);
    }
    setpgid(child, child);
    int status = 0;
    for (;;) {
        pid_t result = waitpid(child, &status, WNOHANG);
        if (result == child) {
            int exit_code = child_exit_code(status);
            terminate_remaining_group(child);
            return exit_code;
        }
        if (result < 0 && errno != EINTR) {
            if (errno == ECHILD) return 128 + shutdown_signal;
            perror("waitpid");
            return 125;
        }
        if (shutdown_signal != 0) return terminate_child_group(child);
        pause_for_child();
    }
}

#endif
