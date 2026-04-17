package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.app.Activity
import android.app.AndroidAppHelper
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

fun setupIntegratedLogin(classLoader: ClassLoader) {

    // 1. Hook su CookieManager (Iniezione ad ogni inizializzazione)
    XposedHelpers.findAndHookMethod("android.webkit.CookieManager", classLoader, "setAcceptCookie", Boolean::class.java, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val appContext = AndroidAppHelper.currentApplication() ?: return
            val token = AuthPrefs.getSavedToken(appContext) ?: return
            injectCookieDynamically(token)
        }
    })

    // 2. Hook Activity (WebView e Refresh)
    XposedHelpers.findAndHookMethod(Activity::class.java, "onCreate", Bundle::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as Activity
            val token = AuthPrefs.getSavedToken(activity)
            val className = activity.javaClass.name

            if (token == null && className.contains("login", ignoreCase = true)) {
                WebLoginManager.showLoginOverlay(activity)
            } else if (token != null) {
                injectCookieDynamically(token)
            }
        }
    })

    // 3. Hook di Rete basso livello (URLConnection)
    runCatching {
        val urlConnClass = XposedHelpers.findClass("java.net.URLConnection", null)
        XposedHelpers.findAndHookMethod(urlConnClass, "setRequestProperty", String::class.java, String::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as String
                // Se Spotify sta già provando a impostare un Cookie, lasciamo fare o sovrascriviamo
                if (key.equals("Cookie", ignoreCase = true)) return

                val appContext = AndroidAppHelper.currentApplication() ?: return
                val token = AuthPrefs.getSavedToken(appContext) ?: return

                val connection = param.thisObject as java.net.URLConnection
                val url = connection.url.toString()

                // Iniettiamo il token solo nelle chiamate verso i domini Spotify
                if (url.contains("spotify.com") || url.contains("scdn.co")) {
                    // Usiamo XposedHelpers.setAdditionalInstanceField per evitare loop se necessario,
                    // ma qui semplicemente aggiungiamo l'header se non è quello dei cookie
                    connection.addRequestProperty("Cookie", "sp_dc=$token")
                }
            }
        })
    }
}

private fun injectCookieDynamically(token: String) {
    runCatching {
        val cm = android.webkit.CookieManager.getInstance()
        cm.setAcceptCookie(true)

        // DOMINI REALI SPOTIFY
        val domains = listOf("spotify.com", ".spotify.com", "accounts.spotify.com")

        domains.forEach { domain ->
            val cookieStr = "sp_dc=$token; domain=$domain; path=/; Max-Age=31536000; Secure; HttpOnly; SameSite=Lax"
            cm.setCookie("https://$domain", cookieStr)
        }
        cm.flush()

        // Verifica su un dominio reale
        val check = cm.getCookie("https://accounts.spotify.com")
        XposedBridge.log("TOKEN-INJECTOR: Verifica CookieManager: ${if (check?.contains("sp_dc") == true) "PRESENTE" else "ASSENTE"}")
    }
}