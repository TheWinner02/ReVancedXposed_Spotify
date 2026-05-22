package io.github.chsbuffer.revancedxposed.spotify

import android.app.Application
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Field

class AdBlockHook(private val app: Application, private val lpparam: LoadPackageParam? = null) {

    fun hook() {
        val cl = lpparam?.classLoader ?: app.classLoader ?: return
        XposedBridge.log("AdBlocker: Refined Engine initialization")

        // ==========================================
        // 1. PATCH DEI FLAGS (Legacy/Compat)
        // ==========================================
        runCatching {
            val flagsClass = cl.loadClass("com.spotify.connectivity.flags.LoadedFlags")
            var identifierField: Field? = null

            XposedBridge.hookAllMethods(flagsClass, "get", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val arg = param.args?.get(0) ?: return

                    // Estrae il campo solo la prima volta, poi usa la cache
                    if (identifierField == null) {
                        identifierField = runCatching {
                            arg.javaClass.getDeclaredField("identifier").apply { isAccessible = true }
                        }.getOrNull()
                    }

                    val key = identifierField?.get(arg) as? String

                    if (key == "ads") {
                        param.setResult(false)
                    }
                }
            })
            XposedBridge.log("AdBlocker: LoadedFlags hook active")
        }

        // ==========================================
        // 2. DISATTIVAZIONE IMPOSTAZIONI PUBBLICITÀ
        // ==========================================
        runCatching {
            val adsClass = cl.loadClass("com.spotify.adsinternal.adscore.AdsSettings")
            XposedBridge.hookAllMethods(adsClass, "isAdsEnabled", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.setResult(false)
                }
            })
            XposedBridge.log("AdBlocker: AdsSettings hook active")
        }

        // ==========================================
        // 3. HIDE AD UI COMPONENTS
        // ==========================================
        runCatching {
            val countdownView = cl.loadClass("com.spotify.adsinternal.playback.video.CountdownBarView")
            XposedHelpers.findAndHookMethod(countdownView, "onMeasure", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Cast diretto a View senza usare reflection per la visibilità
                    val view = param.thisObject as? View ?: return
                    view.visibility = View.GONE

                    // Reflection mantenuta solo per forzare a 0 le dimensioni
                    val setMeasured = View::class.java.getDeclaredMethod("setMeasuredDimension", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
                    setMeasured.isAccessible = true
                    setMeasured.invoke(view, 0, 0)
                    param.setResult(null)
                }
            })
            XposedBridge.log("AdBlocker: CountdownBarView hook active")
        }

        // ==========================================
        // 4. NHB → NATIVE HTTP BLOCK
        // ==========================================

        runCatching {
            val httpConnectionImpl = cl.loadClass("com.spotify.core.http.NativeHttpConnection")
            val httpRequest = cl.loadClass("com.spotify.core.http.HttpRequest")
            val urlField = httpRequest.getDeclaredField("url").apply { isAccessible = true }

            val bannedKeywords = arrayOf("ads", "tracking", "analytics", "crashdump", "ad-logic")
            val bannedPrefixes = arrayOf(
                "https://spclient.wg.spotify.com/ads/",
                "https://audio-ak-spotify-com.akamaized.net/audio/ads/",
                "https://cdn.branch.io",
                "https://dealer.g2.spotify.com" // Attenzione: può rompere Spotify Connect
            )

            XposedBridge.hookAllMethods(httpConnectionImpl, "send", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args?.get(0) ?: return
                    val url = urlField.get(req) as? String ?: return

                    val shouldBlock = bannedKeywords.any { url.contains(it, ignoreCase = true) } ||
                            bannedPrefixes.any { url.startsWith(it) }

                    if (shouldBlock) {
                        XposedBridge.log("AdBlocker: Blocked -> $url")
                        param.setResult(null)
                    }
                }
            })
        }.onFailure { XposedBridge.log("AdBlocker: NHB Error -> ${it.message}") }

    }
}
