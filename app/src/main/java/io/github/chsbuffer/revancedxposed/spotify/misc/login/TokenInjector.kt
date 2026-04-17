package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.app.Activity
import android.app.AndroidAppHelper
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

fun setupIntegratedLogin(classLoader: ClassLoader) {
    val TAG = "TOKEN-INJECTOR"

    // 1. FORZA LO STATO "LOGGED_IN" (Bypass controlli iniziali)
    runCatching {
        val sessionClass = XposedHelpers.findClassIfExists("com.spotify.authentication.login5.Login5Configuration", classLoader)
        if (sessionClass != null) {
            XposedHelpers.findAndHookMethod(sessionClass, "getStoredCredentials", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = AndroidAppHelper.currentApplication()
                    if (AuthPrefs.getSavedToken(context) != null && param.result == null) {
                        XposedBridge.log("$TAG: Forzo bypass credenziali mancanti...")
                        // Qui non settiamo un risultato per non crashare,
                        // ma l'iniezione cookie/header farà il resto
                    }
                }
            })
        }
    }

    // 2. HOOK ACTIVITY: Gestione WebView e Iniezione al volo
    XposedHelpers.findAndHookMethod(Activity::class.java, "onCreate", Bundle::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as Activity
            val token = AuthPrefs.getSavedToken(activity)
            val className = activity.javaClass.name

            if (token == null && className.contains("login", ignoreCase = true)) {
                XposedBridge.log("$TAG: Token mancante, lancio WebView...")
                WebLoginManager.showLoginOverlay(activity)
            } else if (token != null) {
                injectCookieDynamically(token)
            }
        }
    })

    // 3. INIEZIONE RETE (OkHttp) - Più aggressiva
    runCatching {
        val builderClass = XposedHelpers.findClassIfExists($$"okhttp3.Request$Builder", classLoader)
            ?: XposedHelpers.findClassIfExists($$"com.squareup.okhttp.Request$Builder", classLoader)

        if (builderClass != null) {
            XposedBridge.hookAllMethods(builderClass, "build", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val token = AuthPrefs.getSavedToken(AndroidAppHelper.currentApplication()) ?: return

                    // Iniettiamo sia come Header che come parametro se necessario
                    XposedHelpers.callMethod(param.thisObject, "header", "Cookie", "sp_dc=$token")
                    XposedHelpers.callMethod(param.thisObject, "addHeader", "X-Spotify-Session", token)

                    // Log rimosso per prestazioni, scommenta solo per debug:
                    // XposedBridge.log("NETWORK-DEBUG: Header iniettato.")
                }
            })
            XposedBridge.log("$TAG: Hook OkHttp attivo.")
        }
    }

    // 4. BYPASS LOGIC: Forza Spotify a credere di essere loggato
    runCatching {
        // Cerchiamo la classe che decide se mostrare il Login o la Home
        // In molte versioni è legata a "SessionState"
        val sessionClass = XposedHelpers.findClassIfExists("com.spotify.session.SessionState", classLoader)
        if (sessionClass != null) {
            XposedBridge.hookAllMethods(sessionClass, "isLoggedIn", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val token = AuthPrefs.getSavedToken(AndroidAppHelper.currentApplication())
                    if (token != null) {
                        param.result = true // Diciamo all'app: "Sì, l'utente è loggato!"
                    }
                }
            })
            XposedBridge.log("TOKEN-INJECTOR: Forza-Login attivato!")
        }
    }
}

private fun injectCookieDynamically(token: String) {
    runCatching {
        val cm = android.webkit.CookieManager.getInstance()
        cm.setAcceptCookie(true)

        // Domini reali e placeholder per coprire ogni chiamata
        val domains = listOf(
            "spotify.com",
            ".spotify.com",
            "accounts.spotify.com",
            "api.spotify.com",
            "googleusercontent.com/spotify.com/1"
        )

        domains.forEach { domain ->
            val cookieStr = "sp_dc=$token; Domain=$domain; Path=/; Max-Age=31536000; Secure; HttpOnly; SameSite=Lax"
            val url = "https://${domain.removePrefix(".")}"
            cm.setCookie(url, cookieStr)
        }

        cm.flush()

        // Verifica su un dominio standard
        val check = cm.getCookie("https://accounts.spotify.com")
        XposedBridge.log("TOKEN-INJECTOR: CookieManager Status: ${if (check?.contains("sp_dc") == true) "PRESENTE" else "ASSENTE"}")
    }.onFailure {
        XposedBridge.log("TOKEN-INJECTOR: Errore iniezione: ${it.message}")
    }
}