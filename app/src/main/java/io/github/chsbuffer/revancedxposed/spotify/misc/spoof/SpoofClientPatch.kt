package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

private var listener: RequestListener? = null

fun SpoofClient(lpparam: XC_LoadPackage.LoadPackageParam) {
    val port = 4345
    val spotifySha = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"
    val classLoader = lpparam.classLoader
    
    XposedBridge.log("SPOOF-CLIENT: Inizializzazione Spoof Chirurgico (Login-Only)")

    // 1. Signature Spoof (Indispensabile per il login e caricamento lib)
    runCatching {
        val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
        XposedBridge.hookAllMethods(pmClass, "getPackageInfo", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val pkg = param.args[0] as? String ?: return
                if (pkg.contains("spotify")) {
                    val info = param.result as? PackageInfo ?: return
                    @Suppress("DEPRECATION")
                    info.signatures = arrayOf(Signature(hexToBytes(spotifySha)))
                    param.result = info
                }
            }
        })
    }

    // 2. Proxy Listener (L'unico punto dove vive l'identità iOS)
    if (listener == null) {
        runCatching {
            listener = RequestListener(port)
            XposedBridge.log("SPOOF-CLIENT: RequestListener attivo su $port")
        }
    }

    // 3. Hook NativeHttpConnection (Intercettazione Chirurgica)
    runCatching {
        val cl = classLoader
        val httpConnectionImpl = cl.loadClass("com.spotify.core.http.NativeHttpConnection")
        val httpRequest = cl.loadClass("com.spotify.core.http.HttpRequest")
        val urlField = httpRequest.getDeclaredField("url").apply { isAccessible = true }

        XposedBridge.hookAllMethods(
            httpConnectionImpl,
            "send",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args[0]
                    val url = (urlField.get(req) as? String) ?: return

                    // Prevenzione Loop
                    if (url.contains("127.0.0.1")) return

                    // REDIRECT CHIRURGICO PER TOKEN
                    // Solo questa chiamata viene deviata al proxy per ottenere il token iOS.
                    // Tutto il resto dell'app rimane Android Puro per gestire Challenge e UI.
                    if (url.contains("clienttoken.spotify.com/v1/clienttoken")) {
                        val proxyUrl = "http://127.0.0.1:$port/v1/clienttoken"
                        urlField.set(req, proxyUrl)
                        XposedBridge.log("SPOOF-CLIENT: Redirect Token -> $url")
                        return
                    }
                    
                    // Nessun altro intervento. L'app gira come Android originale.
                }
            }
        )
    }
    
    // DexKit rimosso: lasciamo che l'app veda l'hardware reale (Android) 
    // per non mandare in tilt i componenti della UI durante i Challenge.
}

private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
