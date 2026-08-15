#ifndef CODEX_DESKTOP_POSIX_H
#define CODEX_DESKTOP_POSIX_H

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static inline void codex_close_fd(int descriptor) {
    if (descriptor >= 0) close(descriptor);
}

static inline int codex_make_executable(const char *path) {
    struct stat status;
    if (lstat(path, &status) != 0 || !S_ISREG(status.st_mode)) return -1;
    return chmod(path, status.st_mode | S_IXUSR | S_IXGRP | S_IXOTH);
}

static inline int codex_open_url(const char *url) {
    pid_t child = fork();
    if (child < 0) return -1;
    if (child == 0) {
        pid_t opener = fork();
        if (opener < 0) _exit(126);
        if (opener == 0) {
            execlp("xdg-open", "xdg-open", url, (char *)NULL);
            _exit(127);
        }
        _exit(0);
    }
    int status = 0;
    pid_t result;
    do result = waitpid(child, &status, 0); while (result < 0 && errno == EINTR);
    return result == child && WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : -1;
}

static inline int codex_process_start(
    const char *executable,
    const char *argument,
    const char *working_directory,
    codex_process *output,
    char *error,
    size_t error_capacity
) {
    struct stat directory_status;
    int stdin_pipe[2] = {-1, -1};
    int stdout_pipe[2] = {-1, -1};
    int stderr_pipe[2] = {-1, -1};
    if (access(executable, X_OK) != 0) {
        codex_set_error(error, error_capacity, "Codex app server is not executable");
        return -1;
    }
    if (stat(working_directory, &directory_status) != 0 || !S_ISDIR(directory_status.st_mode)) {
        codex_set_error(error, error_capacity, "Desktop working directory does not exist");
        return -1;
    }
    if (pipe(stdin_pipe) != 0 || pipe(stdout_pipe) != 0 || pipe(stderr_pipe) != 0) {
        codex_set_error(error, error_capacity, "Unable to create Codex app-server pipes");
        goto fail;
    }
    pid_t pid = fork();
    if (pid < 0) {
        codex_set_error(error, error_capacity, "Unable to fork the Codex app server");
        goto fail;
    }
    if (pid == 0) {
        if (chdir(working_directory) != 0 ||
            dup2(stdin_pipe[0], STDIN_FILENO) < 0 ||
            dup2(stdout_pipe[1], STDOUT_FILENO) < 0 ||
            dup2(stderr_pipe[1], STDERR_FILENO) < 0) {
            _exit(126);
        }
        codex_close_fd(stdin_pipe[0]);
        codex_close_fd(stdin_pipe[1]);
        codex_close_fd(stdout_pipe[0]);
        codex_close_fd(stdout_pipe[1]);
        codex_close_fd(stderr_pipe[0]);
        codex_close_fd(stderr_pipe[1]);
        execl(executable, executable, argument, (char *)NULL);
        _exit(127);
    }
    codex_close_fd(stdin_pipe[0]);
    codex_close_fd(stdout_pipe[1]);
    codex_close_fd(stderr_pipe[1]);
    fcntl(stdin_pipe[1], F_SETFD, FD_CLOEXEC);
    fcntl(stdout_pipe[0], F_SETFD, FD_CLOEXEC);
    fcntl(stderr_pipe[0], F_SETFD, FD_CLOEXEC);
    output->stdin_write = (codex_handle)stdin_pipe[1];
    output->stdout_read = (codex_handle)stdout_pipe[0];
    output->stderr_read = (codex_handle)stderr_pipe[0];
    output->process = (codex_handle)pid;
    output->job = 0;
    return 0;

fail:
    codex_close_fd(stdin_pipe[0]);
    codex_close_fd(stdin_pipe[1]);
    codex_close_fd(stdout_pipe[0]);
    codex_close_fd(stdout_pipe[1]);
    codex_close_fd(stderr_pipe[0]);
    codex_close_fd(stderr_pipe[1]);
    return -1;
}

static inline ptrdiff_t codex_process_read(codex_handle raw_handle, void *buffer, size_t capacity) {
    ssize_t result;
    do result = read((int)raw_handle, buffer, capacity); while (result < 0 && errno == EINTR);
    return (ptrdiff_t)result;
}

static inline int codex_process_write(codex_handle raw_handle, const void *bytes, size_t count) {
    const unsigned char *cursor = (const unsigned char *)bytes;
    while (count > 0) {
        ssize_t written = write((int)raw_handle, cursor, count);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return -1;
        cursor += written;
        count -= (size_t)written;
    }
    return 0;
}

static inline void codex_process_close(codex_handle raw_handle) {
    if (raw_handle >= 0) close((int)raw_handle);
}

static inline int codex_process_wait(codex_handle raw_process, int *exit_code) {
    int status = 0;
    pid_t result;
    do result = waitpid((pid_t)raw_process, &status, 0); while (result < 0 && errno == EINTR);
    if (result < 0) return -1;
    *exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    return 0;
}

static inline void codex_process_terminate(codex_handle raw_process, codex_handle raw_job) {
    (void)raw_job;
    pid_t pid = (pid_t)raw_process;
    int status = 0;
    pid_t result = waitpid(pid, &status, WNOHANG);
    if (result == pid || (result < 0 && errno == ECHILD)) return;
    kill(pid, SIGTERM);
    struct timespec pause = {0, 10000000};
    for (int attempt = 0; attempt < 300; attempt++) {
        result = waitpid(pid, &status, WNOHANG);
        if (result == pid || (result < 0 && errno == ECHILD)) return;
        nanosleep(&pause, NULL);
    }
    kill(pid, SIGKILL);
    do result = waitpid(pid, &status, 0); while (result < 0 && errno == EINTR);
}

static inline void codex_process_release(codex_handle raw_process, codex_handle raw_job) {
    (void)raw_process;
    (void)raw_job;
}

#endif
