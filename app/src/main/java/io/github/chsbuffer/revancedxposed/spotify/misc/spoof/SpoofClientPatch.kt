package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import android.content.pm.PackageInfo
import android.content.pm.Signature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

private var listener: RequestListener? = null

/**
 * ESPERIMENTO: CLEAN-ANDROID
 * In questo modulo, l'app rimane 100% Android stock.
 * Intercettiamo SOLO il clienttoken tramite proxy per vedere se l'app
 * Android accetta un token iOS senza altri spoofing.
 */
fun SpoofClient(lpparam: XC_LoadPackage.LoadPackageParam) {
    val port = 4345
    val classLoader = lpparam.classLoader
    val spotifySha = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"
    
    XposedBridge.log("SPOOF-EXPERIMENT: Inizio Test Clean-Android (Solo Redirect Token)")

    // 1. Signature Spoof (Sempre necessario per caricare le lib)
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

    // 2. Avvio Proxy (Fabbrica Token iOS)
    if (listener == null) {
        runCatching {
            listener = RequestListener(port)
        }
    }

    // 3. Redirect Chirurgico Token
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

                    if (url.contains("127.0.0.1")) return

                    // REDIRECT SOLO TOKEN
                    if (url.contains("clienttoken.spotify.com/v1/clienttoken")) {
                        urlField.set(req, "http://127.0.0.1:$port/v1/clienttoken")
                        XposedBridge.log("SPOOF-EXPERIMENT: Token Android -> Proxy Redirect")
                        return
                    }
                    
                    // TUTTO IL RESTO (LOGIN, HEADER, DEXTKIT) RIMANE STOCK ANDROID
                }
            }
        )
    }
}

private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
