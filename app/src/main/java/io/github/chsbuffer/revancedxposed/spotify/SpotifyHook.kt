package io.github.chsbuffer.revancedxposed.spotify

import android.app.Application
import io.github.chsbuffer.revancedxposed.ChimeraBridge
import io.github.chsbuffer.revancedxposed.BaseHook
import io.github.chsbuffer.revancedxposed.injectHostClassLoaderToSelf
import io.github.chsbuffer.revancedxposed.spotify.misc.UnlockPremium
import io.github.chsbuffer.revancedxposed.spotify.misc.logout.LogOutPatch
import io.github.chsbuffer.revancedxposed.spotify.misc.privacy.SanitizeSharingLinks
import io.github.chsbuffer.revancedxposed.spotify.misc.widgets.FixThirdPartyLaunchersWidgets

@Suppress("UNCHECKED_CAST")
class SpotifyHook(app: Application) : BaseHook(app) {
    override val hooks = arrayOf(
        ::Extension,
        ::SanitizeSharingLinks,
        ::UnlockPremium,
        //::LogOutPatch,
        ::FixThirdPartyLaunchersWidgets,
        //::NHB
    )

    // ══════════════════════════════════════════════════════
    // EXTENSION LOADER
    // ══════════════════════════════════════════════════════
    fun Extension() {
        injectHostClassLoaderToSelf(this::class.java.classLoader!!, classLoader)
    }

    // ══════════════════════════════════════════════════════
    // NHB → NATIVE HTTP BLOCK
    // ══════════════════════════════════════════════════════
    fun NHB() {
        runCatching {

            val cl = classLoader

            val httpConnectionImpl =
                cl.loadClass("com.spotify.core.http.NativeHttpConnection")

            val httpRequest =
                cl.loadClass("com.spotify.core.http.HttpRequest")

            val urlField = httpRequest.getDeclaredField("url")
            urlField.isAccessible = true

            ChimeraBridge.hookMethod(
                httpConnectionImpl.getDeclaredMethod("send", httpRequest),
                object : ChimeraBridge.XC_MethodHook() {
                    override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                        val req = param.args?.get(0)
                        val url = urlField.get(req) as? String ?: return

                        if (url.contains("ads", true) ||
                            url.contains("tracking", true)
                        ) {
                            ChimeraBridge.log("NHB BLOCK: $url")
                            param.setResult(null)
                        }
                    }
                }
            )

        }.onFailure {
            ChimeraBridge.log("NHB error -> ${it.message}")
        }
    }
}

