package io.github.chsbuffer.revancedxposed.spotify.misc.login

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook

fun SpotifyHook.injectAuthToken() {
    val TAG = "TOKEN-HOOK"
    XposedBridge.log("$TAG: Inizio inizializzazione...")

    val token = AuthPrefs.getSavedToken()
    if (token == null) {
        XposedBridge.log("$TAG: Abortito. Nessun token trovato nelle preferenze.")
        return
    }

    runCatching {
        val cookieManagerClass = Class.forName("android.webkit.CookieManager")

        XposedHelpers.findAndHookMethod(cookieManagerClass, "getCookie", String::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val url = param.args[0] as String

                // Monitoriamo se Spotify passa da qui
                if (url.contains("spotify.com")) {
                    // Logghiamo solo una volta ogni tanto per non intasare il log
                    // XposedBridge.log("$TAG: Intercettata richiesta cookie per: $url")

                    val original = param.result as? String
                    if (original?.contains("sp_dc=") == true) return // Già presente, non facciamo nulla

                    val spDcCookie = "sp_dc=$token"
                    param.result = if (original.isNullOrEmpty()) spDcCookie else "$original; $spDcCookie"

                    XposedBridge.log("$TAG: Iniezione riuscita per $url")
                }
            }
        })
        XposedBridge.log("$TAG: Hook su CookieManager installato correttamente.")
    }.onFailure {
        XposedBridge.log("$TAG: Errore critico durante l'hook: ${it.message}")
    }
}