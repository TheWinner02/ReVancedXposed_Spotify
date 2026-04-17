package io.github.chsbuffer.revancedxposed.spotify.misc.login

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook

fun SpotifyHook.injectAuthToken() {
    val TAG = "TokenInjector"

    // Leggiamo il token salvato nelle prefs del modulo
    val token = AuthPrefs.getSavedToken() ?: return // Legge il token (file o prefs)

    runCatching {
        // Intercettiamo il CookieManager di sistema usato da Spotify
        val cookieManagerClass = Class.forName("android.webkit.CookieManager")

        XposedHelpers.findAndHookMethod(cookieManagerClass, "getCookie", String::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val url = param.args[0] as String

                // Se Spotify chiede i cookie per i suoi domini
                if (url.contains("spotify.com")) {
                    val original = param.result as? String
                    val spDcCookie = "sp_dc=$token"

                    param.result = if (original.isNullOrEmpty()) {
                        spDcCookie
                    } else if (!original.contains("sp_dc=")) {
                        "$original; $spDcCookie"
                    } else {
                        original
                    }
                }
            }
        })
        XposedBridge.log("$TAG -> Iniezione cookie configurata")
    }.onFailure {
        XposedBridge.log("$TAG -> Errore iniezione: ${it.message}")
    }
}