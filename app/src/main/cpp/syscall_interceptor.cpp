#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <string.h>
#include <sys/stat.h>
#include <dlfcn.h>
#include "dobby.h"

#define LOG_TAG "GHOST_SYSCALL"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Global state
static char stock_path[512] = "";

// Original function pointers
typedef int (*open_t)(const char*, int, mode_t);
static open_t orig_open = nullptr;

typedef int (*openat_t)(int, const char*, int, mode_t);
static openat_t orig_openat = nullptr;

typedef int (*fstatat_t)(int, const char*, struct stat*, int);
static fstatat_t orig_fstatat = nullptr;

typedef int (*stat_t)(const char*, struct stat*);
static stat_t orig_stat = nullptr;

typedef int (*lstat_t)(const char*, struct stat*);
static lstat_t orig_lstat = nullptr;

typedef ssize_t (*readlink_t)(const char*, char*, size_t);
static readlink_t orig_readlink = nullptr;

// Helper to check if path belongs to Spotify base APK
bool is_target(const char* path) {
    if (path == nullptr || strlen(path) == 0) return false;
    return (strstr(path, "com.spotify.music") != nullptr) && (strstr(path, "base.apk") != nullptr);
}

// Interceptors
int my_open(const char* pathname, int flags, mode_t mode) {
    if (is_target(pathname) && strlen(stock_path) > 0) {
        LOGI("Ghost: Redirecting open -> %s", stock_path);
        return orig_open(stock_path, flags, mode);
    }
    return orig_open(pathname, flags, mode);
}

int my_openat(int dirfd, const char* pathname, int flags, mode_t mode) {
    if (is_target(pathname) && strlen(stock_path) > 0) {
        LOGI("Ghost: Redirecting openat -> %s", stock_path);
        return orig_openat(dirfd, stock_path, flags, mode);
    }
    return orig_openat(dirfd, pathname, flags, mode);
}

int my_fstatat(int dirfd, const char* pathname, struct stat* buf, int flags) {
    if (is_target(pathname) && strlen(stock_path) > 0) {
        LOGI("Ghost: Redirecting fstatat -> %s", stock_path);
        return orig_fstatat(dirfd, stock_path, buf, flags);
    }
    return orig_fstatat(dirfd, pathname, buf, flags);
}

int my_stat(const char* pathname, struct stat* buf) {
    if (is_target(pathname) && strlen(stock_path) > 0) {
        LOGI("Ghost: Redirecting stat -> %s", stock_path);
        return orig_stat(stock_path, buf);
    }
    return orig_stat(pathname, buf);
}

int my_lstat(const char* pathname, struct stat* buf) {
    if (is_target(pathname) && strlen(stock_path) > 0) {
        LOGI("Ghost: Redirecting lstat -> %s", stock_path);
        return orig_lstat(stock_path, buf);
    }
    return orig_lstat(pathname, buf);
}

ssize_t my_readlink(const char* pathname, char* buf, size_t bufsiz) {
    if (is_target(pathname) && strlen(stock_path) > 0) {
        LOGI("Ghost: Redirecting readlink -> %s", stock_path);
        return orig_readlink(stock_path, buf, bufsiz);
    }
    return orig_readlink(pathname, buf, bufsiz);
}

// JNI Communication
extern "C" JNIEXPORT void JNICALL Java_io_github_chsbuffer_revancedxposed_MainHook_setInternalApkPath(JNIEnv* env, jobject thiz, jstring path) {
    const char* native_path = env->GetStringUTFChars(path, nullptr);
    if (native_path != nullptr) {
        strncpy(stock_path, native_path, sizeof(stock_path) - 1);
        stock_path[sizeof(stock_path) - 1] = '\0';
        LOGI("Ghost: Stock path updated: %s", stock_path);
        env->ReleaseStringUTFChars(path, native_path);
    }
}

void install_syscall_hooks() {
    LOGI("Ghost: Initializing Dobby protection...");

    void* libc = dlopen("libc.so", RTLD_LAZY);
    if (libc) {
        void* open_addr = dlsym(libc, "open");
        if (open_addr) DobbyHook(open_addr, (dobby_dummy_func_t)my_open, (dobby_dummy_func_t*)&orig_open);

        void* openat_addr = dlsym(libc, "openat");
        if (openat_addr) DobbyHook(openat_addr, (dobby_dummy_func_t)my_openat, (dobby_dummy_func_t*)&orig_openat);

        void* fstatat_addr = dlsym(libc, "fstatat");
        if (fstatat_addr) DobbyHook(fstatat_addr, (dobby_dummy_func_t)my_fstatat, (dobby_dummy_func_t*)&orig_fstatat);

        void* stat_addr = dlsym(libc, "stat");
        if (stat_addr) DobbyHook(stat_addr, (dobby_dummy_func_t)my_stat, (dobby_dummy_func_t*)&orig_stat);

        void* lstat_addr = dlsym(libc, "lstat");
        if (lstat_addr) DobbyHook(lstat_addr, (dobby_dummy_func_t)my_lstat, (dobby_dummy_func_t*)&orig_lstat);

        void* readlink_addr = dlsym(libc, "readlink");
        if (readlink_addr) DobbyHook(readlink_addr, (dobby_dummy_func_t)my_readlink, (dobby_dummy_func_t*)&orig_readlink);

        LOGI("Ghost: Dobby hooks installed successfully.");
    }
}
