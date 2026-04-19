package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.app.Activity
import android.app.AndroidAppHelper
import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

private var isJumpingToMain = false

fun setupIntegratedLogin(classLoader: ClassLoader) {
    val TAG = "TOKEN-INJECTOR"
    val RE_USER_AGENT = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"

    // 1. HOOK OKHTTP
    runCatching {
        val builderClass = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", classLoader)
            ?: XposedHelpers.findClassIfExists("com.squareup.okhttp.Request\$Builder", classLoader)

        if (builderClass != null) {
            XposedBridge.hookAllMethods(builderClass, "build", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = AndroidAppHelper.currentApplication() ?: return
                    val token = AuthPrefs.getSavedToken(context) ?: return

                    XposedHelpers.callMethod(param.thisObject, "header", "User-Agent", RE_USER_AGENT)

                    val currentCookie = XposedHelpers.callMethod(param.thisObject, "header", "Cookie") as? String
                    if (currentCookie == null || !currentCookie.contains("sp_dc=")) {
                        val newCookie = if (currentCookie.isNullOrEmpty()) "sp_dc=$token" else "$currentCookie; sp_dc=$token"
                        XposedHelpers.callMethod(param.thisObject, "header", "Cookie", newCookie)
                    }
                }
            })
            XposedBridge.log("$TAG: Hook OkHttp configurato.")
        }
    }

    // DEBUG SERVER RESPONSES
    runCatching {
        val responseClass = XposedHelpers.findClassIfExists("okhttp3.Response", classLoader)
        if (responseClass != null) {
            XposedHelpers.findAndHookMethod(responseClass, "code", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val code = param.result as Int
                    if (code == 401 || code == 403) {
                        XposedBridge.log("$TAG: AUTH-ERROR! Il server ha risposto con $code.")
                    }
                }
            })
        }
    }

    // 2. HOOK ACTIVITY CON PROTEZIONE LOOP E DELAY
    XposedHelpers.findAndHookMethod(Activity::class.java, "onCreate", Bundle::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as Activity
            val token = AuthPrefs.getSavedToken(activity)
            val className = activity.javaClass.name

            if (token != null) {
                injectCookieDynamically(token)
            }

            val isLoginScreen = className.contains("LoginActivity", ignoreCase = true) ||
                    className.contains("OnboardingActivity", ignoreCase = true)

            if (isLoginScreen) {
                if (token != null) {
                    if (isJumpingToMain) return

                    XposedBridge.log("$TAG: Token rilevato! Attendo 2 secondi per la stabilizzazione di DexKit...")
                    isJumpingToMain = true

                    // IL RITARDO MAGICO CHE FERMA IL LOOP
                    activity.window.decorView.postDelayed({
                        try {
                            XposedBridge.log("$TAG: Eseguo salto alla MainActivity...")
                            val launchIntent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
                            launchIntent?.let {
                                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                activity.startActivity(it)
                                activity.finish()
                            }
                        } catch (e: Exception) {
                            isJumpingToMain = false
                        }
                    }, 2000)

                    // Reset di sicurezza dopo 5 secondi
                    activity.window.decorView.postDelayed({ isJumpingToMain = false }, 5000)

                } else {
                    XposedBridge.log("$TAG: Token assente, mostro WebLoginManager.")
                    WebLoginManager.showLoginOverlay(activity)
                }
            } else if (className.contains("MainActivity", ignoreCase = true)) {
                isJumpingToMain = false
            }
        }
    })
}

private fun injectCookieDynamically(token: String) {
    runCatching {
        val cm = android.webkit.CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val domains = listOf(
            "spotify.com",
            ".spotify.com",
            "accounts.spotify.com",
            "api-partner.spotify.com"
        )
        domains.forEach { domain ->
            val cookieStr = "sp_dc=$token; Domain=$domain; Path=/; Max-Age=31536000; Secure; HttpOnly; SameSite=Lax"
            cm.setCookie("https://$domain", cookieStr)
        }
        cm.flush()
    }
}