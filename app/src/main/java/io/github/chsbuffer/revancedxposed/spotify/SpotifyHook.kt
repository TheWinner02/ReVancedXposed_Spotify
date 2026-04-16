package io.github.chsbuffer.revancedxposed.spotify

import android.app.Application
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.chsbuffer.revancedxposed.BaseHook
import io.github.chsbuffer.revancedxposed.injectHostClassLoaderToSelf
import io.github.chsbuffer.revancedxposed.spotify.misc.UnlockPremium
import io.github.chsbuffer.revancedxposed.spotify.misc.logout.LogOutPatch
import io.github.chsbuffer.revancedxposed.spotify.misc.privacy.SanitizeSharingLinks
import io.github.chsbuffer.revancedxposed.spotify.misc.widgets.FixThirdPartyLaunchersWidgets

@Suppress("UNCHECKED_CAST")
class SpotifyHook(app: Application, lpparam: LoadPackageParam) : BaseHook(app, lpparam) {
    override val hooks = arrayOf(
        ::FixFacebookLogin,
        ::Extension,
        ::SanitizeSharingLinks,
        ::UnlockPremium,
        ::LogOutPatch,
        ::FixThirdPartyLaunchersWidgets,
        ::g
    )

    // ══════════════════════════════════════════════════════
    // EXTENSION LOADER
    // ══════════════════════════════════════════════════════
    fun Extension() {
        injectHostClassLoaderToSelf(this::class.java.classLoader!!, classLoader)
    }

    // ══════════════════════════════════════════════════════
    // G → NATIVE HTTP BLOCK
    // ══════════════════════════════════════════════════════
    fun g() {
        runCatching {

            val cl = classLoader

            val httpConnectionImpl =
                cl.loadClass("com.spotify.core.http.NativeHttpConnection")

            val httpRequest =
                cl.loadClass("com.spotify.core.http.HttpRequest")

            val urlField = httpRequest.getDeclaredField("url")
            urlField.isAccessible = true

            XposedBridge.hookAllMethods(
                httpConnectionImpl,
                "send",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val req = param.args[0]
                        val url = urlField.get(req) as? String ?: return

                        if (url.contains("ads", true) ||
                            url.contains("tracking", true)
                        ) {
                            XposedBridge.log("G BLOCK: $url")
                            param.result = null
                        }
                    }
                }
            )

        }.onFailure {
            XposedBridge.log("G error -> ${it.message}")
        }
    }

    // ══════════════════════════════════════════════════════
    // FACEBOOK LOGIN PATCH (Internal Method)
    // ══════════════════════════════════════════════════════
    fun FixFacebookLogin() {
        val TAG = "FixFacebookLogin"

        runCatching {
            val cl = classLoader

            // 1. Cerchiamo la classe che valida il login
            val loginClientClass = runCatching {
                cl.loadClass("com.facebook.login.LoginClient")
            }.getOrNull()

            if (loginClientClass != null) {
                // Cerchiamo il metodo che valida la firma o i pacchetti
                // Spesso si chiama 'validateSignature' o simili
                loginClientClass.declaredMethods.forEach { method ->
                    if (method.name.contains("validate", ignoreCase = true)) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                // Forza la validazione a 'true' (successo)
                                if (method.returnType == Boolean::class.javaPrimitiveType) {
                                    param.result = true
                                    XposedBridge.log("$TAG -> Validazione firma forzata a TRUE")
                                }
                            }
                        })
                    }
                }
            }

            // 2. Manteniamo comunque il trucco del KatanaProxy per sicurezza
            val katanaClass = runCatching {
                cl.loadClass("com.facebook.login.KatanaProxyLoginMethodHandler")
            }.getOrNull()

            katanaClass?.declaredMethods?.forEach { m ->
                if (m.returnType == Int::class.javaPrimitiveType && m.parameterTypes.isNotEmpty()) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            p.result = 0
                            XposedBridge.log("$TAG -> Katana bypassato")
                        }
                    })
                }
            }
        }.onFailure {
            XposedBridge.log("$TAG Error -> ${it.message}")
        }
    }
}

