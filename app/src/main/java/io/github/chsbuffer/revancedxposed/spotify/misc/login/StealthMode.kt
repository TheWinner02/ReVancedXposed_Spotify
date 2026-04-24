package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.pm.PackageManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

fun StealthMode(classLoader: ClassLoader) {
    val myModuleName = "io.github.chsbuffer.revancedxposed"

    // 1. STACKTRACE: Nasconde le classi del modulo
    runCatching {
        XposedHelpers.findAndHookMethod(StackTraceElement::class.java, "getClassName", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val className = param.result as? String ?: return
                if (className.contains(myModuleName) || className.contains("xposed", true) || className.contains("lspatch", true)) {
                    param.result = "android.app.ActivityThread"
                }
            }
        })
    }

    // 2. PACKAGEMANAGER: Nasconde l'app del modulo e LSPosed manager
    runCatching {
        val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
        XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val pkgName = param.args[0] as? String ?: return
                val blacklist = listOf(myModuleName, "org.lsposed.manager", "com.topjohnwu.magisk", "io.github.lsposed.lspatch")
                if (blacklist.any { pkgName == it }) {
                    param.throwable = PackageManager.NameNotFoundException(pkgName)
                }
            }
        })
    }

    // 3. FILESYSTEM: Più mirato per evitare lag
    runCatching {
        XposedHelpers.findAndHookMethod(File::class.java, "exists", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val path = (param.thisObject as File).absolutePath
                if (path.contains("lspatch") || path.contains("libpatch") || path.contains("xposed")) {
                    param.result = false
                }
            }
        })
    }

    // 4. SYSTEM PROPERTIES: Fondamentale per oPatch e Root
    runCatching {
        val systemProperties = XposedHelpers.findClass("android.os.SystemProperties", classLoader)
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as? String ?: return
                val suspects = listOf("ro.lsposed", "xposed", "magisk", "zygisk", "riru", "ro.debuggable", "ro.secure")
                if (suspects.any { key.contains(it, true) }) {
                    // Se l'app chiede se il sistema è sicuro (ro.secure), diciamo di sì (1)
                    if (key == "ro.secure") { param.result = "1"; return }
                    // Per il resto, facciamo finta che non esistano
                    param.result = if (param.args.size > 1) param.args[1] else ""
                }
            }
        }
        XposedHelpers.findAndHookMethod(systemProperties, "get", String::class.java, hook)
        XposedHelpers.findAndHookMethod(systemProperties, "get", String::class.java, String::class.java, hook)
    }

    // 5. MEMORY MAPS (/proc/self/maps) - PROTEZIONE AVANZATA
    runCatching {
        // Intercettiamo il costruttore di File quando l'app prova ad accedere ai maps
        XposedHelpers.findAndHookConstructor(File::class.java, String::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val path = param.args[0] as? String ?: return

                // Se l'app cerca di leggere le mappe di memoria del proprio processo
                if (path.contains("/proc/self/maps") || path.contains("/proc/${android.os.Process.myPid()}/maps")) {
                    // Reindirizziamo la lettura su un file di sistema innocuo.
                    param.args[0] = "/proc/version"
                    XposedBridge.log("STEALTH: Tentativo di lettura maps deviato su /proc/version")
                }
            }
        })
    }

    // 6. NATIVE ROOT CHECKER (Ravelin core)
    runCatching {
        val rootCheckerClass = XposedHelpers.findClass("com.ravelin.core.util.security.RootCheckerNative", classLoader)
        XposedHelpers.findAndHookMethod(rootCheckerClass, "checkForRoot", Array<Any>::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = false
                XposedBridge.log("STEALTH: RootCheckerNative.checkForRoot bypassato")
            }
        })
    }
}
