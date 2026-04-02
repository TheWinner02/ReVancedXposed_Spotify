package io.github.chsbuffer.revancedxposed.spotify

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class AdBlockHook(private val lpparam: LoadPackageParam) {

    fun hook() {
        val cl = lpparam.classLoader
        XposedBridge.log("RE-VANCED XPOSED: Avvio AdBlocker")

        // ==========================================
        // 1. PATCH DEI FLAGS
        // ==========================================
        // Intercetta la classe LoadedFlags e forza il flag "ads" a false.
        try {
            val flagsClass = cl.loadClass("com.spotify.connectivity.flags.LoadedFlags")
            XposedBridge.hookAllMethods(flagsClass, "get", object : XC_MethodHook() {

                // Hook prima che il metodo venga eseguito
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = XposedHelpers.getObjectField(param.args[0], "identifier") as String
                    if (key == "ads") {
                        param.result = false
                    }
                }

                // Hook dopo che il metodo è stato eseguito (doppia sicurezza)
                override fun afterHookedMethod(param: MethodHookParam) {
                    val key = XposedHelpers.getObjectField(param.args[0], "identifier") as String
                    if (key == "ads") {
                        param.result = false
                    }
                }
            })
            XposedBridge.log("AdBlocker: Flag 'ads' forzato a false")
        } catch (e: Throwable) {
            XposedBridge.log("AdBlocker: Errore in LoadedFlags -> ${e.message}")
        }


        // ==========================================
        // 2. HTTP BLOCKER
        // ==========================================
        // Intercetta il client HTTP nativo di Spotify e blocca le richieste verso i server Ads
        try {
            val httpConnectionImpl = cl.loadClass("com.spotify.core.http.NativeHttpConnection")
            val httpRequest = cl.loadClass("com.spotify.core.http.HttpRequest")
            val urlField = httpRequest.getDeclaredField("url")
            urlField.isAccessible = true

            // Qui inseriamo i veri prefissi pubblicitari.
            val bannedPrefixes = arrayOf(
                "https://spclient.wg.spotify.com/ads/",
                "https://spclient.wg.spotify.com/ad-logic/",
                "https://audio-ak-spotify-com.akamaized.net/audio/ads/",
                "https://analytics.spotify.com",
                "https://tracking.spotify.com",
                "https://log.spotify.com",
                "https://crashdump.spotify.com",
                "https://dealer.g2.spotify.com",
                "https://gew1-dealer.g2.spotify.com",
                "https://gew1-dealer-ssl.spotify.com"
            )

            XposedBridge.hookAllMethods(httpConnectionImpl, "send", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args[0]
                    val url = urlField.get(req) as String

                    if (bannedPrefixes.any { url.startsWith(it) }) {
                        XposedBridge.log("AdBlocker: Bloccata richiesta nativa HTTP -> $url")
                        param.result = null // Questo uccide la richiesta alla radice
                    }
                }
            })
            XposedBridge.log("AdBlocker: Native HTTP Blocker attivato")
        } catch (e: Throwable) {
            XposedBridge.log("AdBlocker: Errore in Http Blocker -> ${e.message}")
        }
    }
}