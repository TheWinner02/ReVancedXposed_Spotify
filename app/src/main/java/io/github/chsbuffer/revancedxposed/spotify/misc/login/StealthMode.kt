package io.github.chsbuffer.revancedxposed.spotify.misc.login

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.io.File

fun StealthMode(classLoader: ClassLoader) {

    // 1. STACKTRACE: Nasconde le tracce del modulo nei crash log
    runCatching {
        XposedHelpers.findAndHookMethod(StackTraceElement::class.java, "getClassName", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val className = param.result as? String ?: return
                val suspects = listOf("xposed", "lsposed", "revanced", "chsbuffer", "patch")
                if (suspects.any { className.contains(it, ignoreCase = true) }) {
                    param.result = "android.app.ActivityThread"
                }
            }
        })
    }

    // 2. PACKAGEMANAGER: Nasconde l'esistenza del modulo stesso
    runCatching {
        val myModuleName = "io.github.chsbuffer.revancedxposed"
        val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)

        XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val pkgName = param.args[0] as String
                if (pkgName == myModuleName || pkgName.contains("xposed") || pkgName.contains("lsposed")) {
                    param.throwable = android.content.pm.PackageManager.NameNotFoundException(pkgName)
                }
            }
        })
    }

    // 3. FILESYSTEM: Nasconde file e librerie sospette
    runCatching {
        XposedHelpers.findAndHookMethod(File::class.java, "getPath", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val path = param.result as? String ?: return
                if (path.contains("libpatch", true) || path.contains("xposed", true)) {
                    param.result = "/system/lib/libart.so" // Reindirizziamo a una lib di sistema innocua
                }
            }
        })

        XposedHelpers.findAndHookMethod(File::class.java, "exists", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val file = param.thisObject as File
                if (file.name.contains("libpatch", true) || file.name.contains("xposed", true)) {
                    param.result = false
                }
            }
        })
    }

    // 4. WEBVIEW: Disabilita il debug per evitare che Spotify "veda" l'ispezione della pagina di login
    runCatching {
        val webViewClass = XposedHelpers.findClass("android.webkit.WebView", classLoader)
        XposedHelpers.findAndHookMethod(webViewClass, "setWebContentsDebuggingEnabled", Boolean::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args[0] = false
            }
        })
    }

    // 5. SYSTEM PROPERTIES: Nasconde i flag di oPatch/Xposed/Root
    runCatching {
        val systemProperties = XposedHelpers.findClass("android.os.SystemProperties", classLoader)

        // Hookiamo il metodo 'get' che accetta solo la chiave
        XposedHelpers.findAndHookMethod(systemProperties, "get", String::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as String
                // Lista nera di chiavi sospette da mascherare
                if (key.startsWith("ro.lsposed") || key.contains("xposed") || key.contains("magisk")) {
                    param.result = "" // Restituiamo vuoto come se la proprietà non esistesse
                }
            }
        })

        // Hookiamo anche il 'get' che accetta un valore di default (comune in Android)
        XposedHelpers.findAndHookMethod(systemProperties, "get", String::class.java, String::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as String
                if (key.startsWith("ro.lsposed") || key.contains("xposed") || key.contains("magisk")) {
                    param.result = param.args[1] // Restituiamo il valore di default passato dall'app
                }
            }
        })
    }
}