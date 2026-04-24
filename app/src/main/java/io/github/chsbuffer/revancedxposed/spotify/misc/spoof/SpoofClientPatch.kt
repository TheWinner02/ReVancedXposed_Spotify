package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import android.content.pm.PackageInfo
import android.content.pm.Signature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

private var listener: RequestListener? = null

fun SpoofClient(lpparam: XC_LoadPackage.LoadPackageParam) {
    val port = 4345
    // Identità iOS Master (Allineata con sorgenti Pro)
    val iosClientId = "58bd3c95768941ea9eb4350aaa033eb3"
    val iosUserAgent = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"
    val iosStaticDeviceId = "2A084F20-1307-3AE0-83C8-AE5CA4AB5CD0"
    val spotifySha = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"
    
    val classLoader = lpparam.classLoader
    XposedBridge.log("SPOOF-CLIENT: Inizializzazione Total Identity Bridge (Login5 + Token)")

    // 1. Signature Spoof (Indispensabile per il login)
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

    // 2. Proxy Listener (iOS Token Factory)
    if (listener == null) {
        runCatching {
            listener = RequestListener(port)
            XposedBridge.log("SPOOF-CLIENT: RequestListener attivo su $port")
        }
    }

    // 3. Hook NativeHttpConnection (Il "Cervello" dello Spoofing)
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

                    // TARGET 1: REDIRECT CHIRURGICO PER TOKEN
                    if (url.contains("clienttoken.spotify.com/v1/clienttoken")) {
                        val proxyUrl = "http://127.0.0.1:$port/v1/clienttoken"
                        urlField.set(req, proxyUrl)
                        XposedBridge.log("SPOOF-CLIENT: Redirect Token -> $url")
                        return
                    }

                    // TARGET 2: SPOOFING IDENTITÀ PER LOGIN E API VITALI
                    // Colpiamo login5 (autenticazione) e spclient (dati premium)
                    if (url.contains("login5.spotify.com") || url.contains("spclient.wg.spotify.com")) {
                        
                        // Sostituzione parametri URL (Android -> iOS)
                        if (url.contains("android")) {
                            val newUrl = url.replace("platform=android", "platform=ios")
                                            .replace("device=android", "device=ios")
                                            .replace("os=android", "os=ios")
                            urlField.set(req, newUrl)
                        }

                        // Iniezione Header Master (Indispensabile per login5)
                        runCatching {
                            val headersField = req.javaClass.declaredFields.find { 
                                it.type == Map::class.java || it.type.name.contains("headers", ignoreCase = true) 
                            }
                            headersField?.let {
                                it.isAccessible = true
                                @Suppress("UNCHECKED_CAST")
                                val map = it.get(req) as? MutableMap<String, String>
                                map?.let { m ->
                                    m["User-Agent"] = iosUserAgent
                                    m["App-Platform"] = "ios"
                                    m["X-Client-Id"] = iosClientId
                                    m["X-Spotify-Device-Id"] = iosStaticDeviceId
                                    XposedBridge.log("SPOOF-CLIENT: Header iOS MASTER iniettati per $url")
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
