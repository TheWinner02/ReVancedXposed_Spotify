#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <dlfcn.h>
#include <sys/system_properties.h>
#include "dobby.h"

#define LOG_TAG "ReVancedXposedNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static char internal_apk_path[512] = "";

bool is_spotify_apk(const char* pathname) {
    if (pathname == nullptr || strlen(internal_apk_path) == 0) return false;
    return (strstr(pathname, "com.spotify.music") != nullptr) && (strstr(pathname, "base.apk") != nullptr);
}

// Function types
typedef int (*open_t)(const char*, int, ...);
typedef int (*openat_t)(int, const char*, int, ...);
typedef int (*access_t)(const char*, int);
typedef int (*fstatat_t)(int, const char*, struct stat*, int);
typedef int (*stat_t)(const char*, struct stat*);
typedef int (*lstat_t)(const char*, struct stat*);
typedef ssize_t (*readlink_t)(const char*, char*, size_t);
typedef int (*__system_property_get_t)(const char*, char*);

// Original pointers
static open_t orig_open = nullptr;
static openat_t orig_openat = nullptr;
static access_t orig_access = nullptr;
static fstatat_t orig_fstatat = nullptr;
static stat_t orig_stat = nullptr;
static lstat_t orig_lstat = nullptr;
static readlink_t orig_readlink = nullptr;
static __system_property_get_t orig_prop_get = nullptr;

// Hooks
int my_open(const char* pathname, int flags, mode_t mode) {
    if (is_spotify_apk(pathname)) {
        LOGI("Redirecting open: %s -> %s", pathname, internal_apk_path);
        return orig_open(internal_apk_path, flags, mode);
    }
    return orig_open(pathname, flags, mode);
}

int my_openat(int dirfd, const char* pathname, int flags, mode_t mode) {
    if (is_spotify_apk(pathname)) {
        LOGI("Redirecting openat: %s -> %s", pathname, internal_apk_path);
        return orig_openat(dirfd, internal_apk_path, flags, mode);
    }
    return orig_openat(dirfd, pathname, flags, mode);
}

int my_access(const char* pathname, int mode) {
    if (is_spotify_apk(pathname)) {
        LOGI("Redirecting access: %s -> %s", pathname, internal_apk_path);
        return orig_access(internal_apk_path, mode);
    }
    return orig_access(pathname, mode);
}

int my_fstatat(int dirfd, const char* pathname, struct stat* buf, int flags) {
    if (is_spotify_apk(pathname)) {
        LOGI("Redirecting fstatat: %s -> %s", pathname, internal_apk_path);
        return orig_fstatat(dirfd, internal_apk_path, buf, flags);
    }
    return orig_fstatat(dirfd, pathname, buf, flags);
}

int my_stat(const char* pathname, struct stat* buf) {
    if (is_spotify_apk(pathname)) {
        LOGI("Redirecting stat: %s -> %s", pathname, internal_apk_path);
        return orig_stat(internal_apk_path, buf);
    }
    return orig_stat(pathname, buf);
}

int my_lstat(const char* pathname, struct stat* buf) {
    if (is_spotify_apk(pathname)) {
        LOGI("Redirecting lstat: %s -> %s", pathname, internal_apk_path);
        return orig_lstat(internal_apk_path, buf);
    }
    return orig_lstat(pathname, buf);
}

ssize_t my_readlink(const char* pathname, char* buf, size_t bufsiz) {
    if (is_spotify_apk(pathname)) {
        LOGI("Redirecting readlink: %s", pathname);
        return orig_readlink(internal_apk_path, buf, bufsiz);
    }
    return orig_readlink(pathname, buf, bufsiz);
}

// Spoof system properties to hide root/debugging
int my_system_property_get(const char* name, char* value) {
    int ret = orig_prop_get(name, value);
    if (name != nullptr && value != nullptr) {
        if (strstr(name, "ro.debuggable") || strstr(name, "ro.secure")) {
            if (strstr(name, "ro.debuggable")) strcpy(value, "0");
            if (strstr(name, "ro.secure")) strcpy(value, "1");
            LOGI("Spoofed property: %s -> %s", name, value);
        }
    }
    return ret;
}

extern "C" JNIEXPORT void JNICALL Java_io_github_chsbuffer_revancedxposed_MainHook_setInternalApkPath(JNIEnv* env, jobject thiz, jstring path) {
    const char* native_path = env->GetStringUTFChars(path, nullptr);
    if (native_path != nullptr) {
        strncpy(internal_apk_path, native_path, sizeof(internal_apk_path) - 1);
        internal_apk_path[sizeof(internal_apk_path) - 1] = '\0';
        LOGI("Native path updated: %s", internal_apk_path);
        env->ReleaseStringUTFChars(path, native_path);
    }
}

void install_hooks() {
    LOGI("Installing advanced native hooks with Dobby...");

    void* libc = dlopen("libc.so", RTLD_LAZY);
    if (libc) {
        DobbyHook(dlsym(libc, "open"), (dobby_dummy_func_t)my_open, (dobby_dummy_func_t*)&orig_open);
        DobbyHook(dlsym(libc, "openat"), (dobby_dummy_func_t)my_openat, (dobby_dummy_func_t*)&orig_openat);
        DobbyHook(dlsym(libc, "access"), (dobby_dummy_func_t)my_access, (dobby_dummy_func_t*)&orig_access);
        DobbyHook(dlsym(libc, "fstatat"), (dobby_dummy_func_t)my_fstatat, (dobby_dummy_func_t*)&orig_fstatat);

        void* stat_addr = dlsym(libc, "stat");
        if (stat_addr) DobbyHook(stat_addr, (dobby_dummy_func_t)my_stat, (dobby_dummy_func_t*)&orig_stat);

        void* lstat_addr = dlsym(libc, "lstat");
        if (lstat_addr) DobbyHook(lstat_addr, (dobby_dummy_func_t)my_lstat, (dobby_dummy_func_t*)&orig_lstat);

        void* readlink_addr = dlsym(libc, "readlink");
        if (readlink_addr) DobbyHook(readlink_addr, (dobby_dummy_func_t)my_readlink, (dobby_dummy_func_t*)&orig_readlink);

        void* prop_get_addr = dlsym(libc, "__system_property_get");
        if (prop_get_addr) DobbyHook(prop_get_addr, (dobby_dummy_func_t)my_system_property_get, (dobby_dummy_func_t*)&orig_prop_get);
    }

    LOGI("Advanced native hooks installed.");
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("Native library loaded. JNI_OnLoad called.");
    install_hooks();
    return JNI_VERSION_1_6;
}
