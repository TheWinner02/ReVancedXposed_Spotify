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
            // Cerchiamo la classe usando il classLoader dell'app
            val clazz = runCatching {
                classLoader.loadClass("com.facebook.login.KatanaProxyLoginMethodHandler")
            }.getOrNull()

            if (clazz == null) {
                XposedBridge.log("$TAG -> Classe Facebook non trovata (normale se non usi FB)")
                return
            }

            // Cerchiamo il metodo tryAuthorize (che restituisce Int e ha parametri)
            val method = clazz.declaredMethods.firstOrNull { m ->
                m.returnType == Int::class.javaPrimitiveType && m.parameterTypes.isNotEmpty()
            }

            if (method != null) {
                XposedBridge.log("$TAG -> Hooking ${clazz.name}.${method.name}")
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Forza il fallback sul browser (returnEarly 0)
                        param.result = 0
                    }
                })
            }
        }.onFailure {
            XposedBridge.log("$TAG -> Errore critico: ${it.message}")
        }
    }
}

