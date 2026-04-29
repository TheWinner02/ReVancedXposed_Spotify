#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include <sys/stat.h>
#include <dlfcn.h>
#include <pthread.h>
#include <time.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include "dobby.h"

#define LOG_TAG "FirebaseCrashlytics"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Global state
static char stock_path[512] = "";
static char maps_cache_path[512] = "";
static pthread_mutex_t maps_mutex = PTHREAD_MUTEX_INITIALIZER;
static time_t last_maps_update = 0;
static time_t last_status_update = 0;

// Thread-local recursion guard
static thread_local bool is_internal_call = false;

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

typedef int (*access_t)(const char*, int);
static access_t orig_access = nullptr;

typedef int (*faccessat_t)(int, const char*, int, int);
static faccessat_t orig_faccessat = nullptr;

typedef FILE* (*fopen_t)(const char*, const char*);
static fopen_t orig_fopen = nullptr;

// Helper to check if path belongs to Spotify base APK
bool is_target(const char* path) {
    if (path == nullptr || strlen(path) == 0) return false;
    return (strstr(path, "com.spotify.music") != nullptr) && (strstr(path, "base.apk") != nullptr);
}

// Low-level open to bypass hooks
int sys_open(const char* path, int flags) {
    return syscall(__NR_openat, AT_FDCWD, path, flags, 0);
}

// Function to generate a clean version of /proc/self/maps
const char* get_clean_maps() {
    if (strlen(maps_cache_path) == 0) return "/proc/self/maps";

    static char clean_maps_path[512];
    snprintf(clean_maps_path, sizeof(clean_maps_path), "%s/maps_clean", maps_cache_path);

    pthread_mutex_lock(&maps_mutex);
    time_t now = time(nullptr);
    if (now - last_maps_update < 1) {
        pthread_mutex_unlock(&maps_mutex);
        return clean_maps_path;
    }

    int real_fd = sys_open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (real_fd < 0) {
        pthread_mutex_unlock(&maps_mutex);
        return "/proc/self/maps";
    }

    FILE* real_maps = fdopen(real_fd, "r");
    FILE* clean_maps = fopen(clean_maps_path, "we"); // fopen is fine here as it opens our cache file

    if (real_maps && clean_maps) {
        char line[1024];
        const char* forbidden[] = {"pine", "revanced", "ghost", "crashlytics", "dexkit", "system.bin", "settings.bin", "chimera", "xposed", "lsposed", "chsbuffer"};
        int forbidden_count = sizeof(forbidden) / sizeof(forbidden[0]);

        while (fgets(line, sizeof(line), real_maps)) {
            bool skip = false;
            for (int i = 0; i < forbidden_count; i++) {
                if (strstr(line, forbidden[i])) {
                    skip = true;
                    break;
                }
            }
            if (!skip) fputs(line, clean_maps);
        }
    }

    if (real_maps) fclose(real_maps); else if (real_fd >= 0) close(real_fd);
    if (clean_maps) fclose(clean_maps);
    
    last_maps_update = now;
    pthread_mutex_unlock(&maps_mutex);
    return clean_maps_path;
}

// Function to generate a clean version of /proc/self/status
const char* get_clean_status() {
    if (strlen(maps_cache_path) == 0) return "/proc/self/status";

    static char clean_status_path[512];
    snprintf(clean_status_path, sizeof(clean_status_path), "%s/status_clean", maps_cache_path);

    pthread_mutex_lock(&maps_mutex);
    time_t now = time(nullptr);
    if (now - last_status_update < 1) {
        pthread_mutex_unlock(&maps_mutex);
        return clean_status_path;
    }

    int real_fd = sys_open("/proc/self/status", O_RDONLY | O_CLOEXEC);
    if (real_fd < 0) {
        pthread_mutex_unlock(&maps_mutex);
        return "/proc/self/status";
    }

    FILE* real_status = fdopen(real_fd, "r");
    FILE* clean_status = fopen(clean_status_path, "we");

    if (real_status && clean_status) {
        char line[1024];
        while (fgets(line, sizeof(line), real_status)) {
            if (strstr(line, "TracerPid:")) {
                fputs("TracerPid:\t0\n", clean_status);
            } else if (strstr(line, "State:") && (strstr(line, "T (tracing stop)") || strstr(line, "t (tracing stop)"))) {
                fputs("State:\tS (sleeping)\n", clean_status);
            } else {
                fputs(line, clean_status);
            }
        }
    }

    if (real_status) fclose(real_status); else if (real_fd >= 0) close(real_fd);
    if (clean_status) fclose(clean_status);

    last_status_update = now;
    pthread_mutex_unlock(&maps_mutex);
    return clean_status_path;
}

// Interceptors
int my_open(const char* pathname, int flags, mode_t mode) {
    if (is_internal_call) return orig_open(pathname, flags, mode);

    if (pathname && strstr(pathname, "/proc/self/maps")) {
        return orig_open(get_clean_maps(), flags, mode);
    }
    if (pathname && strstr(pathname, "/proc/self/status")) {
        return orig_open(get_clean_status(), flags, mode);
    }
    if (is_target(pathname) && strlen(stock_path) > 0) {
        LOGI("SystemCore: IO redirection active");
        return orig_open(stock_path, flags, mode);
    }
    return orig_open(pathname, flags, mode);
}

int my_openat(int dirfd, const char* pathname, int flags, mode_t mode) {
    if (is_internal_call) return orig_openat(dirfd, pathname, flags, mode);

    if (pathname && strstr(pathname, "/proc/self/maps")) {
        return orig_openat(dirfd, get_clean_maps(), flags, mode);
    }
    if (pathname && strstr(pathname, "/proc/self/status")) {
        return orig_openat(dirfd, get_clean_status(), flags, mode);
    }
    if (is_target(pathname) && strlen(stock_path) > 0) {
        return orig_openat(dirfd, stock_path, flags, mode);
    }
    return orig_openat(dirfd, pathname, flags, mode);
}

int my_fstatat(int dirfd, const char* pathname, struct stat* buf, int flags) {
    if (pathname && strstr(pathname, "/proc/self/maps")) return orig_fstatat(dirfd, get_clean_maps(), buf, flags);
    if (pathname && strstr(pathname, "/proc/self/status")) return orig_fstatat(dirfd, get_clean_status(), buf, flags);
    if (is_target(pathname) && strlen(stock_path) > 0) return orig_fstatat(dirfd, stock_path, buf, flags);
    return orig_fstatat(dirfd, pathname, buf, flags);
}

int my_stat(const char* pathname, struct stat* buf) {
    if (pathname && strstr(pathname, "/proc/self/maps")) return orig_stat(get_clean_maps(), buf);
    if (pathname && strstr(pathname, "/proc/self/status")) return orig_stat(get_clean_status(), buf);
    if (is_target(pathname) && strlen(stock_path) > 0) return orig_stat(stock_path, buf);
    return orig_stat(pathname, buf);
}

int my_lstat(const char* pathname, struct stat* buf) {
    if (pathname && strstr(pathname, "/proc/self/maps")) return orig_lstat(get_clean_maps(), buf);
    if (pathname && strstr(pathname, "/proc/self/status")) return orig_lstat(get_clean_status(), buf);
    if (is_target(pathname) && strlen(stock_path) > 0) return orig_lstat(stock_path, buf);
    return orig_lstat(pathname, buf);
}

ssize_t my_readlink(const char* pathname, char* buf, size_t bufsiz) {
    if (is_target(pathname) && strlen(stock_path) > 0) return orig_readlink(stock_path, buf, bufsiz);
    
    ssize_t res = orig_readlink(pathname, buf, bufsiz);
    if (res > 0) {
        if (strstr(buf, "maps_clean")) {
            strncpy(buf, "/proc/self/maps", bufsiz);
            return (ssize_t)strlen("/proc/self/maps");
        } else if (strstr(buf, "status_clean")) {
            strncpy(buf, "/proc/self/status", bufsiz);
            return (ssize_t)strlen("/proc/self/status");
        }
    }
    return res;
}

int my_access(const char* pathname, int mode) {
    if (pathname && (strstr(pathname, "/su") || strstr(pathname, "/magisk") || strstr(pathname, "busybox"))) return -1;
    return orig_access(pathname, mode);
}

int my_faccessat(int dirfd, const char* pathname, int mode, int flags) {
    if (pathname && (strstr(pathname, "/su") || strstr(pathname, "/magisk") || strstr(pathname, "busybox"))) return -1;
    return orig_faccessat(dirfd, pathname, mode, flags);
}

FILE* my_fopen(const char* pathname, const char* mode) {
    if (pathname && strstr(pathname, "/proc/self/maps")) return orig_fopen(get_clean_maps(), mode);
    if (pathname && strstr(pathname, "/proc/self/status")) return orig_fopen(get_clean_status(), mode);
    if (pathname && (strstr(pathname, "/su") || strstr(pathname, "/magisk") || strstr(pathname, "busybox"))) return nullptr;
    return orig_fopen(pathname, mode);
}

// JNI Communication
extern "C" JNIEXPORT void JNICALL Java_io_github_chsbuffer_revancedxposed_MainHook_setInternalApkPath(JNIEnv* env, jobject thiz, jstring path) {
    const char* native_path = env->GetStringUTFChars(path, nullptr);
    if (native_path != nullptr) {
        strncpy(stock_path, native_path, sizeof(stock_path) - 1);
        stock_path[sizeof(stock_path) - 1] = '\0';
        env->ReleaseStringUTFChars(path, native_path);
    }
}

extern "C" void set_maps_cache_path(const char* path) {
    if (path != nullptr) {
        strncpy(maps_cache_path, path, sizeof(maps_cache_path) - 1);
        maps_cache_path[sizeof(maps_cache_path) - 1] = '\0';
    }
}

extern "C" JNIEXPORT void JNICALL Java_io_github_chsbuffer_revancedxposed_MainHook_setMapsCachePath(JNIEnv* env, jobject thiz, jstring path) {
    const char* native_path = env->GetStringUTFChars(path, nullptr);
    set_maps_cache_path(native_path);
    env->ReleaseStringUTFChars(path, native_path);
}

static JNINativeMethod main_hook_methods[] = {
    {"setInternalApkPath", "(Ljava/lang/String;)V", (void*)Java_io_github_chsbuffer_revancedxposed_MainHook_setInternalApkPath},
    {"setMapsCachePath", "(Ljava/lang/String;)V", (void*)Java_io_github_chsbuffer_revancedxposed_MainHook_setMapsCachePath}
};

extern "C" bool register_main_hook_natives(JNIEnv* env, jclass clazz) {
    return env->RegisterNatives(clazz, main_hook_methods, 2) >= 0;
}

void install_syscall_hooks() {
    LOGI("SystemCore: Integrity engine starting...");
    void* libc = dlopen("libc.so", RTLD_LAZY);
    if (libc) {
        DobbyHook(dlsym(libc, "open"), (dobby_dummy_func_t)my_open, (dobby_dummy_func_t*)&orig_open);
        DobbyHook(dlsym(libc, "openat"), (dobby_dummy_func_t)my_openat, (dobby_dummy_func_t*)&orig_openat);
        DobbyHook(dlsym(libc, "fstatat"), (dobby_dummy_func_t)my_fstatat, (dobby_dummy_func_t*)&orig_fstatat);
        DobbyHook(dlsym(libc, "stat"), (dobby_dummy_func_t)my_stat, (dobby_dummy_func_t*)&orig_stat);
        DobbyHook(dlsym(libc, "lstat"), (dobby_dummy_func_t)my_lstat, (dobby_dummy_func_t*)&orig_lstat);
        DobbyHook(dlsym(libc, "readlink"), (dobby_dummy_func_t)my_readlink, (dobby_dummy_func_t*)&orig_readlink);
        DobbyHook(dlsym(libc, "access"), (dobby_dummy_func_t)my_access, (dobby_dummy_func_t*)&orig_access);
        DobbyHook(dlsym(libc, "faccessat"), (dobby_dummy_func_t)my_faccessat, (dobby_dummy_func_t*)&orig_faccessat);
        DobbyHook(dlsym(libc, "fopen"), (dobby_dummy_func_t)my_fopen, (dobby_dummy_func_t*)&orig_fopen);
        LOGI("SystemCore: Engine [ ONLINE ]");
    }
}