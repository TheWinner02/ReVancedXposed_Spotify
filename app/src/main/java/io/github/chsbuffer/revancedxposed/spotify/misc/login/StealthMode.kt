package io.github.chsbuffer.revancedxposed.spotify.misc.login

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

fun StealthMode(classLoader: ClassLoader) {
    val TAG = "STEALTH-SHIELD"

    // 1. PULIZIA DELLO STACKTRACE (Il metodo di rilevamento più comune)
    // Quando Spotify genera un errore interno per controllare chi lo sta chiamando,
    // noi intercettiamo la lettura e nascondiamo i nomi "sospetti".
    runCatching {
        XposedHelpers.findAndHookMethod(StackTraceElement::class.java, "getClassName", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val className = param.result as String? ?: return

                if (className.contains("xposed", ignoreCase = true) ||
                    className.contains("lsposed", ignoreCase = true) ||
                    className.contains("revanced", ignoreCase = true) ||
                    className.contains("edxp", ignoreCase = true) ||
                    className.contains("chsbuffer", ignoreCase = true)) { // Il tuo pacchetto

                    // Sostituiamo il nostro codice con una classe di sistema innocua
                    param.result = "android.app.ActivityThread"
                }
            }
        })
        XposedBridge.log("$TAG: StackTrace Obfuscator attivato.")
    }

    // 2. ACCECAMENTO DEL PACKAGEMANAGER (Nascondiamo il modulo)
    // Se Spotify cerca di vedere se hai installato moduli o app sospette,
    // facciamo credere ad Android che l'app non esista.
    runCatching {
        val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", null)

        // Hook su getPackageInfo (quando cerca un'app specifica)
        XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val pkgName = param.args[0] as String

                // INSERISCI QUI IL NOME DEL PACCHETTO DEL TUO MODULO
                val myModuleName = "io.github.chsbuffer.revancedxposed"

                if (pkgName == myModuleName || pkgName.contains("xposed")) {
                    XposedBridge.log("$TAG: Spotify ha cercato di rilevare $pkgName. Bloccato!")
                    // Lanciamo l'eccezione nativa di Android "App non trovata"
                    param.throwable = android.content.pm.PackageManager.NameNotFoundException(pkgName)
                }
            }
        })
    }

    // 3. BYPASS DELLE VARIABILI DI SISTEMA (System.getProperty)
    // Nascondiamo eventuali proprietà iniettate dai framework Xposed
    runCatching {
        XposedHelpers.findAndHookMethod(System::class.java, "getProperty", String::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as String
                if (key.contains("vxp") || key.contains("xposed")) {
                    param.result = null
                }
            }
        })
    }

    runCatching {
        val webViewClass = XposedHelpers.findClass("android.webkit.WebView", classLoader)
        XposedHelpers.findAndHookMethod(webViewClass, "setWebContentsDebuggingEnabled", Boolean::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.args[0] = false // Impedisce a Spotify di fare il debug della nostra WebView
            }
        })
    }
}