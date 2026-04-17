package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.app.Activity
import android.app.AndroidAppHelper
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

fun setupIntegratedLogin(classLoader: ClassLoader) {
    val TAG = "TOKEN-INJECTOR"

    // 1. FORZA IL TOKEN IN OGNI MOMENTO (Hook su CookieManager)
    // Hookiamo il metodo 'setAcceptCookie' perché Spotify lo chiama sempre all'avvio
    XposedHelpers.findAndHookMethod("android.webkit.CookieManager", classLoader, "setAcceptCookie", Boolean::class.java, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val appContext = AndroidAppHelper.currentApplication() ?: return
            val token = AuthPrefs.getSavedToken(appContext) ?: return

            XposedBridge.log("$TAG: Inizializzazione forzata rilevata. Inietto cookie...")
            injectCookieDynamically(token)
        }
    })

    // 2. HOOK ACTIVITY PER WEBVIEW E REFRESH
    XposedHelpers.findAndHookMethod(Activity::class.java, "onCreate", Bundle::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as Activity
            val token = AuthPrefs.getSavedToken(activity)
            val className = activity.javaClass.name

            if (token == null && className.contains("login", ignoreCase = true)) {
                WebLoginManager.showLoginOverlay(activity)
            } else if (token != null) {
                // Se abbiamo il token, forziamo l'iniezione anche qui
                injectCookieDynamically(token)
            }
        }
    })

    // 3. HOOK DI RETE (PIANO E): Hook su intercettazione Header di basso livello
    // Proviamo a colpire la classe che gestisce le proprietà delle connessioni
    runCatching {
        val urlConnClass = XposedHelpers.findClass("java.net.URLConnection", null)
        XposedHelpers.findAndHookMethod(urlConnClass, "setRequestProperty", String::class.java, String::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as String
                if (key == "Cookie") return // Evitiamo loop infiniti

                val appContext = AndroidAppHelper.currentApplication() ?: return
                val token = AuthPrefs.getSavedToken(appContext) ?: return

                val connection = param.thisObject as java.net.URLConnection
                val url = connection.url.toString()

                if (url.contains("spotify.com")) {
                    connection.setRequestProperty("Cookie", "sp_dc=$token")
                    // Log per essere sicuri al 100% che la rete sia colpita
                    XposedBridge.log("NETWORK-DEBUG: Header iniettato via URLConnection")
                }
            }
        })
    }
}

// Funzione di supporto per iniettare il cookie nel CookieManager
private fun injectCookieDynamically(token: String) {
    runCatching {
        val cm = android.webkit.CookieManager.getInstance()

        // Permettiamo i cookie di terze parti (spesso necessario per Spotify)
        cm.setAcceptCookie(true)

        // Domini su cui Spotify si aspetta il token
        val domains = listOf("spotify.com", ".spotify.com", "accounts.spotify.com")

        domains.forEach { domain ->
            // Pulizia: rimuoviamo eventuali spazi o punti extra
            val cookieStr = "sp_dc=$token; domain=$domain; path=/; Max-Age=31536000; Secure; HttpOnly; SameSite=Lax"

            // Usiamo l'URL HTTPS completo per l'iniezione, altrimenti Android 15 lo scarta
            val url = "https://${domain.removePrefix(".")}"
            cm.setCookie(url, cookieStr)
        }

        cm.flush()

        XposedBridge.log("TOKEN-INJECTOR: Tentata iniezione su: ${domains.joinToString()}")

        // Verifica migliorata
        val check = cm.getCookie("https://spotify.com")
        XposedBridge.log("TOKEN-INJECTOR: Verifica CookieManager per spotify.com: ${if (check?.contains("sp_dc") == true) "PRESENTE" else "ASSENTE"}")

        if (check?.contains("sp_dc") == false) {
            XposedBridge.log("TOKEN-INJECTOR: Il sistema ha scartato il cookie. Provo metodo forzato...")
            // Tentativo disperato senza attributi extra
            cm.setCookie("https://spotify.com", "sp_dc=$token")
        }

    }.onFailure {
        XposedBridge.log("TOKEN-INJECTOR: Errore: ${it.message}")
    }
}