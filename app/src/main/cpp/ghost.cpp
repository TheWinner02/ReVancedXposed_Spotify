#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <dlfcn.h>
#include "dobby.h"

#define LOG_TAG "GHOST_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// External declaration for syscall interceptor
void install_syscall_hooks();

void install_libc_hooks() {
    LOGI("Installing Libc hooks...");
    // We'll use these to redirect file access to the stock APK
    // This is the fallback if syscall hooking is too complex or fails
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("Ghost library loaded. Starting initialization...");

    // Step 1: Initialize Dobby and Syscall Interception
    install_syscall_hooks();

    // Step 2: Initialize Libc hooks for safety
    install_libc_hooks();

    LOGI("Ghost initialization completed.");
    return JNI_VERSION_1_6;
}
