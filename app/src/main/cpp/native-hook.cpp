#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <dlfcn.h>
#include "dobby.h"

#define LOG_TAG "ReVancedXposedNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Placeholder for the original APK path
static const char* original_apk_path = "/sdcard/Download/base.apk";

// Function to check if the path is the Spotify base.apk
bool is_spotify_apk(const char* pathname) {
    if (pathname == nullptr) return false;
    return (strstr(pathname, "com.spotify.music") != nullptr) && (strstr(pathname, "base.apk") != nullptr);
}

// Function types for the original functions
typedef int (*open_t)(const char*, int, ...);
typedef int (*openat_t)(int, const char*, int, ...);
typedef int (*access_t)(const char*, int);
typedef int (*fstatat_t)(int, const char*, struct stat*, int);

// Original function pointers
static open_t orig_open = nullptr;
static openat_t orig_openat = nullptr;
static access_t orig_access = nullptr;
static fstatat_t orig_fstatat = nullptr;

// Hook for open
int my_open(const char* pathname, int flags, mode_t mode) {
    if (is_spotify_apk(pathname)) {
        LOGI("Redirecting open: %s -> %s", pathname, original_apk_path);
        return orig_open(original_apk_path, flags, mode);
    }
    return orig_open(pathname, flags, mode);
}

// Hook for openat
int my_openat(int dirfd, const char* pathname, int flags, mode_t mode) {
    if (is_spotify_apk(pathname)) {
        LOGI("Redirecting openat: %s -> %s", pathname, original_apk_path);
        return orig_openat(dirfd, original_apk_path, flags, mode);
    }
    return orig_openat(dirfd, pathname, flags, mode);
}

// Hook for access
int my_access(const char* pathname, int mode) {
    if (is_spotify_apk(pathname)) {
        LOGI("Redirecting access: %s -> %s", pathname, original_apk_path);
        return orig_access(original_apk_path, mode);
    }
    return orig_access(pathname, mode);
}

// Hook for fstatat
int my_fstatat(int dirfd, const char* pathname, struct stat* buf, int flags) {
    if (is_spotify_apk(pathname)) {
        LOGI("Redirecting fstatat: %s -> %s", pathname, original_apk_path);
        return orig_fstatat(dirfd, original_apk_path, buf, flags);
    }
    return orig_fstatat(dirfd, pathname, buf, flags);
}

void install_hooks() {
    LOGI("Installing native hooks with Dobby...");

    void* libc = dlopen("libc.so", RTLD_LAZY);
    if (!libc) {
        LOGE("Failed to open libc.so: %s", dlerror());
        return;
    }

    void* open_addr = dlsym(libc, "open");
    void* openat_addr = dlsym(libc, "openat");
    void* access_addr = dlsym(libc, "access");
    void* fstatat_addr = dlsym(libc, "fstatat");

    if (open_addr) DobbyHook(open_addr, (dobby_dummy_func_t)my_open, (dobby_dummy_func_t*)&orig_open);
    if (openat_addr) DobbyHook(openat_addr, (dobby_dummy_func_t)my_openat, (dobby_dummy_func_t*)&orig_openat);
    if (access_addr) DobbyHook(access_addr, (dobby_dummy_func_t)my_access, (dobby_dummy_func_t*)&orig_access);
    if (fstatat_addr) DobbyHook(fstatat_addr, (dobby_dummy_func_t)my_fstatat, (dobby_dummy_func_t*)&orig_fstatat);

    LOGI("Native hooks installation attempt finished. Open addr: %p", open_addr);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("Native library loaded. JNI_OnLoad called.");
    install_hooks();
    return JNI_VERSION_1_6;
}
