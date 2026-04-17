package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.app.Activity
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook

fun SpotifyHook.setupIntegratedLogin() {
    val token = AuthPrefs.getSavedToken()

    // 1. INIEZIONE COOKIE (Se abbiamo il token)
    if (token != null) {
        XposedHelpers.findAndHookMethod("android.webkit.CookieManager", classLoader,
            "getCookie", String::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val url = param.args[0] as String
                    if (url.contains("spotify.com")) {
                        val original = param.result as? String
                        param.result = if (original.isNullOrEmpty()) "sp_dc=$token"
                        else if (!original.contains("sp_dc=")) "$original; sp_dc=$token"
                        else original
                    }
                }
            })
    }

    // 2. TRIGGER WEBVIEW (Se NON abbiamo il token)
    // Usiamo una classe comune che Spotify apre sempre al login
    val loginClassName = "com.spotify.login.loginframework.AuthActivity"

    XposedHelpers.findAndHookMethod(android.app.Activity::class.java, "onCreate", Bundle::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as Activity
            val className = activity.javaClass.name

            // Se siamo nella schermata di login e non abbiamo il token...
            if (className.contains("login", ignoreCase = true) && AuthPrefs.getSavedToken() == null) {
                XposedBridge.log("LOGIN-TRIGGER: Opening WebView Overlay...")
                WebLoginManager.showLoginOverlay(activity)
            }
        }
    })
}