package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.app.Activity
import android.app.AndroidAppHelper
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

fun setupIntegratedLogin(classLoader: ClassLoader) {
    val TAG = "TOKEN-INJECTOR"

    // 1. BYPASS CRASH FLAGS (Correzione per l'errore nel tuo log)
    runCatching {
        val flagsClass = XposedHelpers.findClassIfExists("com.spotify.connectivity.flags.LoadedFlags", classLoader)
        if (flagsClass == null) {
            XposedBridge.log("$TAG: Classe LoadedFlags non trovata, applico bypass stabilità.")
        }
    }

    // 2. HOOK OKHTTP - Iniezione Header (Priorità Massima)
    runCatching {
        val builderClass = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", classLoader)
            ?: XposedHelpers.findClassIfExists("com.squareup.okhttp.Request\$Builder", classLoader)

        if (builderClass != null) {
            XposedBridge.hookAllMethods(builderClass, "build", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val token = AuthPrefs.getSavedToken(AndroidAppHelper.currentApplication()) ?: return

                    // Iniezione multipla per bypassare i filtri geografici
                    XposedHelpers.callMethod(param.thisObject, "header", "Cookie", "sp_dc=$token")
                    XposedHelpers.callMethod(param.thisObject, "header", "Authorization", "Bearer $token")
                    XposedHelpers.callMethod(param.thisObject, "header", "X-Spotify-Session", token)
                }
            })
            XposedBridge.log("$TAG: Hook OkHttp rinforzato.")
        }
    }

    // 3. HOOK ACTIVITY - Forza iniezione Cookie all'apertura
    XposedHelpers.findAndHookMethod(Activity::class.java, "onCreate", Bundle::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as Activity
            val token = AuthPrefs.getSavedToken(activity)
            val className = activity.javaClass.name

            if (token != null) {
                injectCookieDynamically(token)

                // Se siamo bloccati nella LoginActivity ma abbiamo il token,
                // proviamo a forzare l'app a ricaricare la sessione
                if (className.contains("LoginActivity", ignoreCase = true)) {
                    XposedBridge.log("$TAG: Forzo aggiornamento sessione nella LoginActivity...")
                }
            } else if (className.contains("login", ignoreCase = true)) {
                WebLoginManager.showLoginOverlay(activity)
            }
        }
    })
}

private fun injectCookieDynamically(token: String) {
    runCatching {
        val cm = android.webkit.CookieManager.getInstance()
        cm.setAcceptCookie(true)

        // URL critici di Spotify per il login e la verifica area geografica
        val domains = listOf(
            "spotify.com",
            ".spotify.com",
            "accounts.spotify.com",
            "api.spotify.com"
        )

        domains.forEach { domain ->
            // Iniezione con parametri di persistenza massimi
            val cookieStr = "sp_dc=$token; Domain=$domain; Path=/; Max-Age=31536000; Secure; HttpOnly; SameSite=Lax"
            cm.setCookie("https://$domain", cookieStr)
        }
        cm.flush()

        val check = cm.getCookie("https://accounts.spotify.com")
        XposedBridge.log("TOKEN-INJECTOR: Verifica CookieManager: ${if (check?.contains("sp_dc") == true) "PRESENTE" else "ASSENTE"}")
    }
}