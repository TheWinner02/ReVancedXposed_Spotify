package io.github.chsbuffer.revancedxposed.spotify.misc.login

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

fun StealthMode(classLoader: ClassLoader) {

    // 1. STACKTRACE: Nascondi i colpevoli nei log di errore
    runCatching {
        XposedHelpers.findAndHookMethod(StackTraceElement::class.java, "getClassName", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val className = param.result as? String? ?: return
                if (className.contains("xposed", true) || className.contains("revanced", true) ||
                    className.contains("chsbuffer", true) || className.contains("patch", true)) {
                    param.result = "android.app.ActivityThread"
                }
            }
        })
    }

    // 2. PACKAGEMANAGER: Nascondi il modulo e Xposed
    runCatching {
        // Usiamo la classe base IPackageManager per intercettare tutto a livello più basso
        val pmClassName = $$"android.content.pm.IPackageManager$Stub$Proxy"
        val myModuleName = "io.github.chsbuffer.revancedxposed"

        XposedHelpers.findAndHookMethod(pmClassName, classLoader, "getPackageInfo",
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkgName = param.args[0] as String
                    if (pkgName == myModuleName || pkgName.contains("xposed") || pkgName.contains("lsposed")) {
                        param.throwable = android.content.pm.PackageManager.NameNotFoundException(pkgName)
                    }
                }
            })
    }

    // 3. FILESYSTEM (OTTIMIZZATO): Nascondi libpatch.so e cartelle sospette
    runCatching {
        XposedHelpers.findAndHookMethod(java.io.File::class.java, "exists", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val file = param.thisObject as java.io.File
                val name = file.name // Usiamo il nome invece del path intero per velocità
                if (name.contains("libpatch", true) || name.contains("xposed", true) ||
                    name.contains("revanced", true)) {
                    param.result = false
                }
            }
        })
    }

    // 4. WEBVIEW: Impedisci il debugging remoto
    runCatching {
        val webViewClass = XposedHelpers.findClass("android.webkit.WebView", classLoader)
        XposedHelpers.findAndHookMethod(webViewClass, "setWebContentsDebuggingEnabled", Boolean::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args[0] = false
            }
        })
    }

    // 5. SYSTEM PROPERTIES: Nascondi i flag di oPatch/Xposed
    runCatching {
        val systemProperties = XposedHelpers.findClass("android.os.SystemProperties", classLoader)
        XposedHelpers.findAndHookMethod(systemProperties, "get", String::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as String
                if (key.contains("xposed", true) || key.contains("patch", true) || key.contains("revanced", true)) {
                    param.result = ""
                }
            }
        })
    }
}