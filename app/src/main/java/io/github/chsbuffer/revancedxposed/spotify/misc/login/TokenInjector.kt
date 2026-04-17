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
    // 2. INIEZIONE DI RETE DINAMICA (Header Injection con ricerca classi)
    runCatching {
        // Cerchiamo la classe che funge da Builder per OkHttp
        // Di solito è l'unica che ha il metodo "header" con due stringhe e ritorna se stessa
        val requestBuilderClass = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", classLoader)
            ?: XposedHelpers.findClassIfExists("com.squareup.okhttp.Request\$Builder", classLoader)

        if (requestBuilderClass != null) {
            XposedBridge.hookAllMethods(requestBuilderClass, "build", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val appContext = AndroidAppHelper.currentApplication() ?: return
                    val token = AuthPrefs.getSavedToken(appContext) ?: return

                    // Usiamo "header" in modo dinamico
                    runCatching {
                        XposedHelpers.callMethod(param.thisObject, "header", "Cookie", "sp_dc=$token")
                        // LOG DI CONFERMA (Assicurati che non sia commentato!)
                        XposedBridge.log("NETWORK-DEBUG: Header sp_dc iniettato con successo!")
                    }
                }
            })
            XposedBridge.log("TOKEN-INJECTOR: Hook OkHttp installato.")
        } else {
            XposedBridge.log("TOKEN-INJECTOR: ATTENZIONE - OkHttp non trovato, provo scansione profonda...")
            // Se arriviamo qui, Spotify ha offuscato pesantemente OkHttp.
            // Possiamo provare a hookare direttamente il CookieManager interno di OkHttp se necessario.
        }
    }.onFailure {
        XposedBridge.log("TOKEN-INJECTOR: Errore critico OkHttp: ${it.message}")
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