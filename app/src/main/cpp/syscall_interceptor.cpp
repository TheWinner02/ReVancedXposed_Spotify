#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include <sys/stat.h>
#include <dlfcn.h>
#include <pthread.h>
#include <time.h>
#include "dobby.h"

#define LOG_TAG "GHOST_SYSCALL"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Global state
static char stock_path[512] = "";
static char maps_cache_path[512] = "";
static pthread_mutex_t maps_mutex = PTHREAD_MUTEX_INITIALIZER;
static time_t last_maps_update = 0;

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

// Function to generate a clean version of /proc/self/maps
const char* get_clean_maps() {
    if (strlen(maps_cache_path) == 0 || orig_fopen == nullptr) return "/proc/self/maps";

    static char clean_maps_path[512];
    snprintf(clean_maps_path, sizeof(clean_maps_path), "%s/maps_clean", maps_cache_path);

    pthread_mutex_lock(&maps_mutex);
    
    time_t now = time(nullptr);
    if (now - last_maps_update < 1) { // Aggiorna massimo una volta al secondo
        pthread_mutex_unlock(&maps_mutex);
        return clean_maps_path;
    }

    FILE* real_maps = orig_fopen("/proc/self/maps", "re");
    if (!real_maps) {
        pthread_mutex_unlock(&maps_mutex);
        return "/proc/self/maps";
    }

    FILE* clean_maps = orig_fopen(clean_maps_path, "we");
    if (!clean_maps) {
        fclose(real_maps);
        pthread_mutex_unlock(&maps_mutex);
        return "/proc/self/maps";
    }

    char line[1024];
    const char* forbidden[] = {"pine", "revanced", "ghost", "crashlytics", "dexkit", "system.bin", "settings.bin", "chimera", "xposed", "lsposed", "magisk", "busybox", "zygote", "chsbuffer", "supersu", "XposedBridge"};
    int forbidden_count = sizeof(forbidden) / sizeof(forbidden[0]);

    while (fgets(line, sizeof(line), real_maps)) {
        bool skip = false;
        for (int i = 0; i < forbidden_count; i++) {
            if (strstr(line, forbidden[i])) {
                skip = true;
                break;
            }
        }
        if (!skip) {
            fputs(line, clean_maps);
        }
    }

    fclose(real_maps);
    fclose(clean_maps);
    last_maps_update = now;
    pthread_mutex_unlock(&maps_mutex);
    
    return clean_maps_path;
}

// Function to generate a clean version of /proc/self/status (spoof TracerPid: 0)
const char* get_clean_status() {
    if (strlen(maps_cache_path) == 0 || orig_fopen == nullptr) return "/proc/self/status";

    static char clean_status_path[512];
    snprintf(clean_status_path, sizeof(clean_status_path), "%s/status_clean", maps_cache_path);

    pthread_mutex_lock(&maps_mutex);
    
    // Riutilizziamo last_maps_update per semplicità o creiamo uno dedicato
    FILE* real_status = orig_fopen("/proc/self/status", "re");
    if (!real_status) {
        pthread_mutex_unlock(&maps_mutex);
        return "/proc/self/status";
    }

    FILE* clean_status = orig_fopen(clean_status_path, "we");
    if (!clean_status) {
        fclose(real_status);
        pthread_mutex_unlock(&maps_mutex);
        return "/proc/self/status";
    }

    char line[1024];
    while (fgets(line, sizeof(line), real_status)) {
        if (strstr(line, "TracerPid:")) {
            fputs("TracerPid:\t0\n", clean_status);
        } else {
            fputs(line, clean_status);
        }
    }

    fclose(real_status);
    fclose(clean_status);
    pthread_mutex_unlock(&maps_mutex);
    
    return clean_status_path;
}

// Interceptors
int my_open(const char* pathname, int flags, mode_t mode) {
    if (pathname && strstr(pathname, "/proc/self/maps")) {
        LOGI("Ghost: Redirecting open(/proc/self/maps)");
        return orig_open(get_clean_maps(), flags, mode);
    }
    if (pathname && strstr(pathname, "/proc/self/status")) {
        LOGI("Ghost: Redirecting open(/proc/self/status)");
        return orig_open(get_clean_status(), flags, mode);
    }
    if (is_target(pathname) && strlen(stock_path) > 0) {
        LOGI("Ghost: Redirecting open -> %s", stock_path);
        return orig_open(stock_path, flags, mode);
    }
    return orig_open(pathname, flags, mode);
}

int my_openat(int dirfd, const char* pathname, int flags, mode_t mode) {
    if (pathname && strstr(pathname, "/proc/self/maps")) {
        LOGI("Ghost: Redirecting openat(/proc/self/maps)");
        return orig_openat(dirfd, get_clean_maps(), flags, mode);
    }
    if (pathname && strstr(pathname, "/proc/self/status")) {
        LOGI("Ghost: Redirecting openat(/proc/self/status)");
        return orig_openat(dirfd, get_clean_status(), flags, mode);
    }
    if (is_target(pathname) && strlen(stock_path) > 0) {
        LOGI("Ghost: Redirecting openat -> %s", stock_path);
        return orig_openat(dirfd, stock_path, flags, mode);
    }
    return orig_openat(dirfd, pathname, flags, mode);
}

int my_fstatat(int dirfd, const char* pathname, struct stat* buf, int flags) {
    if (pathname && strstr(pathname, "/proc/self/maps")) {
        return orig_fstatat(dirfd, get_clean_maps(), buf, flags);
    }
    if (pathname && strstr(pathname, "/proc/self/status")) {
        return orig_fstatat(dirfd, get_clean_status(), buf, flags);
    }
    if (is_target(pathname) && strlen(stock_path) > 0) {
        LOGI("Ghost: Redirecting fstatat -> %s", stock_path);
        return orig_fstatat(dirfd, stock_path, buf, flags);
    }
    return orig_fstatat(dirfd, pathname, buf, flags);
}

int my_stat(const char* pathname, struct stat* buf) {
    if (pathname && strstr(pathname, "/proc/self/maps")) {
        return orig_stat(get_clean_maps(), buf);
    }
    if (pathname && strstr(pathname, "/proc/self/status")) {
        return orig_stat(get_clean_status(), buf);
    }
    if (is_target(pathname) && strlen(stock_path) > 0) {
        LOGI("Ghost: Redirecting stat -> %s", stock_path);
        return orig_stat(stock_path, buf);
    }
    return orig_stat(pathname, buf);
}

int my_lstat(const char* pathname, struct stat* buf) {
    if (pathname && strstr(pathname, "/proc/self/maps")) {
        return orig_lstat(get_clean_maps(), buf);
    }
    if (pathname && strstr(pathname, "/proc/self/status")) {
        return orig_lstat(get_clean_status(), buf);
    }
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
    ssize_t res = orig_readlink(pathname, buf, bufsiz);
    if (res > 0) {
        if (strstr(buf, "maps_clean")) {
            const char* fake = "/proc/self/maps";
            size_t len = strlen(fake);
            if (len < bufsiz) {
                strncpy(buf, fake, bufsiz);
                return (ssize_t)len;
            }
        } else if (strstr(buf, "status_clean")) {
            const char* fake = "/proc/self/status";
            size_t len = strlen(fake);
            if (len < bufsiz) {
                strncpy(buf, fake, bufsiz);
                return (ssize_t)len;
            }
        }
    }
    return res;
}

int my_access(const char* pathname, int mode) {
    if (pathname && (strstr(pathname, "/su") || strstr(pathname, "/magisk") || strstr(pathname, "busybox"))) {
        return -1; // File non accessibile / non trovato
    }
    return orig_access(pathname, mode);
}

int my_faccessat(int dirfd, const char* pathname, int mode, int flags) {
    if (pathname && (strstr(pathname, "/su") || strstr(pathname, "/magisk") || strstr(pathname, "busybox"))) {
        return -1;
    }
    return orig_faccessat(dirfd, pathname, mode, flags);
}

// Anti-Detection: Nasconde le nostre librerie da /proc/self/maps
FILE* my_fopen(const char* pathname, const char* mode) {
    if (pathname && strstr(pathname, "/proc/self/maps") != nullptr) {
        LOGI("Ghost: liborbit is scanning memory maps. Redirection active.");
        return orig_fopen(get_clean_maps(), mode);
    }
    if (pathname && strstr(pathname, "/proc/self/status") != nullptr) {
        LOGI("Ghost: Anti-debug redirection active for /proc/self/status.");
        return orig_fopen(get_clean_status(), mode);
    }

    // Root Cloaking: nascondiamo binari comuni di root
    if (pathname && (strstr(pathname, "/su") || strstr(pathname, "/magisk") || strstr(pathname, "busybox"))) {
        return nullptr; // File non trovato
    }

    return orig_fopen(pathname, mode);
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

extern "C" void set_maps_cache_path(const char* path) {
    if (path != nullptr) {
        strncpy(maps_cache_path, path, sizeof(maps_cache_path) - 1);
        maps_cache_path[sizeof(maps_cache_path) - 1] = '\0';
        LOGI("Ghost: Maps cache path set (internal): %s", maps_cache_path);
    }
}

extern "C" JNIEXPORT void JNICALL Java_io_github_chsbuffer_revancedxposed_MainHook_setMapsCachePath(JNIEnv* env, jobject thiz, jstring path) {
    const char* native_path = env->GetStringUTFChars(path, nullptr);
    set_maps_cache_path(native_path);
    env->ReleaseStringUTFChars(path, native_path);
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

        void* access_addr = dlsym(libc, "access");
        if (access_addr) DobbyHook(access_addr, (dobby_dummy_func_t)my_access, (dobby_dummy_func_t*)&orig_access);

        void* faccessat_addr = dlsym(libc, "faccessat");
        if (faccessat_addr) DobbyHook(faccessat_addr, (dobby_dummy_func_t)my_faccessat, (dobby_dummy_func_t*)&orig_faccessat);

        void* fopen_addr = dlsym(libc, "fopen");
        if (fopen_addr) DobbyHook(fopen_addr, (dobby_dummy_func_t)my_fopen, (dobby_dummy_func_t*)&orig_fopen);

        LOGI("Ghost: Dobby hooks installed successfully.");
    }
}
