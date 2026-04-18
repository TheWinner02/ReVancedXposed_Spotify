package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.app.Activity
import android.app.AndroidAppHelper
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

fun setupIntegratedLogin(classLoader: ClassLoader) {
    val TAG = "TOKEN-INJECTOR"

    // 1. HOOK OKHTTP - Iniezione Cookie (Il metodo più sicuro)
    runCatching {
        val builderClass = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", classLoader)
            ?: XposedHelpers.findClassIfExists("com.squareup.okhttp.Request\$Builder", classLoader)

        if (builderClass != null) {
            XposedBridge.hookAllMethods(builderClass, "build", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Recuperiamo il contesto in modo sicuro
                    val context = AndroidAppHelper.currentApplication() ?: return
                    val token = AuthPrefs.getSavedToken(context) ?: return

                    // Recuperiamo l'header Cookie esistente per non sovrascrivere altri dati utili
                    val currentCookie = XposedHelpers.callMethod(param.thisObject, "header", "Cookie") as? String

                    val newCookie = if (currentCookie.isNullOrEmpty()) {
                        "sp_dc=$token"
                    } else if (!currentCookie.contains("sp_dc=")) {
                        "$currentCookie; sp_dc=$token"
                    } else {
                        null // sp_dc già presente, non facciamo nulla
                    }

                    if (newCookie != null) {
                        XposedHelpers.callMethod(param.thisObject, "header", "Cookie", newCookie)
                    }
                }
            })
            XposedBridge.log("$TAG: Hook OkHttp configurato (Iniezione Cookie).")
        }
    }

    // 2. HOOK ACTIVITY - Gestione Overlay e Re-init
    XposedHelpers.findAndHookMethod(Activity::class.java, "onCreate", Bundle::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as Activity
            val token = AuthPrefs.getSavedToken(activity)
            val className = activity.javaClass.name

            // Se abbiamo il token, iniettiamolo nel CookieManager (per i componenti Web di Spotify)
            if (token != null) {
                injectCookieDynamically(token)

                // Se siamo ancora nella LoginActivity nonostante abbiamo il token,
                // probabilmente serve un restart o un'azione manuale
                if (className.contains("LoginActivity", ignoreCase = true)) {
                    XposedBridge.log("$TAG: Token presente, ma siamo in LoginActivity. L'app dovrebbe aggiornarsi...")
                }
            }
            // Se NON abbiamo il token e siamo in una schermata di login, mostriamo l'overlay
            else if (className.contains("LoginActivity", ignoreCase = true) ||
                className.contains("OnboardingActivity", ignoreCase = true)) {
                XposedBridge.log("$TAG: Token assente, avvio WebLoginManager...")
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