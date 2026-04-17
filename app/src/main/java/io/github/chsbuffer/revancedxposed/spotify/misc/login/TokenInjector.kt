package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.app.Activity
import android.app.AndroidAppHelper
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

fun setupIntegratedLogin(classLoader: ClassLoader) {

    // 1. HOOK PER IL LOGIN AUTOMATICO E INIEZIONE
    XposedHelpers.findAndHookMethod(Activity::class.java, "onCreate", Bundle::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as Activity
            val context = activity.applicationContext

            // Proviamo a leggere il token dal pacchetto locale
            val token = AuthPrefs.getSavedToken(context)
            val className = activity.javaClass.name

            // CASO A: Non abbiamo il token e siamo nella schermata di login
            if (token == null && className.contains("login", ignoreCase = true)) {
                XposedBridge.log("TOKEN-INJECTOR: Token mancante, apro WebView...")
                WebLoginManager.showLoginOverlay(activity)
                return
            }

            // CASO B: Abbiamo il token, iniettiamolo nei cookie per questa sessione
            if (token != null) {
                injectCookieDynamically(token)
            }
        }
    })
    // 2. INIEZIONE DI RETE DIRETTA (Header Injection)
    runCatching {
        val requestBuilderClass = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", classLoader)
        if (requestBuilderClass != null) {
            XposedBridge.hookAllMethods(requestBuilderClass, "build", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Recuperiamo il token (AuthPrefs deve essere accessibile)
                    // Usiamo il context dell'app se possibile, o un fallback
                    val token = AuthPrefs.getSavedToken(AndroidAppHelper.currentApplication()) ?: return

                    val builder = param.thisObject
                    // Inseriamo il cookie direttamente nell'Header HTTP
                    XposedHelpers.callMethod(builder, "header", "Cookie", "sp_dc=$token")

                    // Opzionale: decommenta per vedere se le chiamate vengono patchate
                    XposedBridge.log("NETWORK-DEBUG: Header sp_dc iniettato in OkHttp")
                }
            })
            XposedBridge.log("TOKEN-INJECTOR: Hook OkHttp installato con successo!")
        } else {
            XposedBridge.log("TOKEN-INJECTOR: Classe OkHttp non trovata (potrebbe essere offuscata)")
        }
    }.onFailure {
        XposedBridge.log("TOKEN-INJECTOR: Errore nell'hook OkHttp: ${it.message}")
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