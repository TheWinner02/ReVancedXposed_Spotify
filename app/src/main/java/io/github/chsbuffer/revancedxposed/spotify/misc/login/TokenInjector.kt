package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.app.Activity
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook

fun SpotifyHook.setupIntegratedLogin() {

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
}

// Funzione di supporto per iniettare il cookie nel CookieManager
private fun injectCookieDynamically(token: String) {
    runCatching {
        val cm = android.webkit.CookieManager.getInstance()
        val cookieStr = "sp_dc=$token; Domain=.spotify.com; Path=/; Secure; HttpOnly"
        cm.setCookie(".spotify.com", cookieStr)
        // XposedBridge.log("TOKEN-INJECTOR: Cookie iniettato correttamente")
    }.onFailure {
        XposedBridge.log("TOKEN-INJECTOR: Errore iniezione cookie: ${it.message}")
    }
}