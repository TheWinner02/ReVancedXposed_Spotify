package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import android.content.pm.PackageInfo
import android.content.pm.Signature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge

private var listener: RequestListener? = null

fun SpoofClient(lpparam: XC_LoadPackage.LoadPackageParam) {
    val port = 4345
    // Identità iOS Master (Allineata con IosClientTokenService v8.8.84)
    val iosClientId = "58bd3c95768941ea9eb4350aaa033eb3"
    val iosUserAgent = "Spotify/8.8.84 iOS/17.7.2 (iPhone16,1)"
    val iosStaticDeviceId = "2A084F20-1307-3AE0-83C8-AE5CA4AB5CD0"
    val spotifySha = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"
    
    val classLoader = lpparam.classLoader
    XposedBridge.log("SPOOF-CLIENT: Inizializzazione Strategia ReVanced Pro (Master Alignment)")

    // 1. Signature Spoof (Indispensabile per caricamento lib native e login)
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

    // 2. Proxy Listener (SOLO per ClientToken - Come da sorgente ReVanced)
    if (listener == null) {
        runCatching {
            listener = RequestListener(port)
            XposedBridge.log("SPOOF-CLIENT: Proxy ClientToken attivo su $port")
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

                    // REDIRECT CLIENT-TOKEN (Cuore del Bypass Premium)
                    if (url.contains("clienttoken.spotify.com/v1/clienttoken")) {
                        urlField.set(req, "http://127.0.0.1:$port/v1/clienttoken")
                        XposedBridge.log("SPOOF-CLIENT: Redirect Token -> $url")
                        return
                    }

                    // SPOOFING HEADER PER LOGIN E DATI PREMIUM (Invisibile e veloce)
                    if (url.contains("login5.spotify.com") || url.contains("spclient.wg.spotify.com")) {
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
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    // 4. Deep Spoof DexKit (Sostituzione Mirata del Platform Name)
    // Sostituiamo "android" con "ios" solo nei metodi che ritornano la piattaforma
    runCatching { System.loadLibrary("dexkit") }
    Thread {
        runCatching {
            val apkPath = lpparam.appInfo.sourceDir ?: return@Thread
            DexKitBridge.create(apkPath).use { bridge ->
                bridge.findMethod {
                    matcher {
                        returnType = "java.lang.String"
                        usingStrings("android")
                    }
                }.forEach { mData ->
                    runCatching {
                        val method = mData.getMethodInstance(classLoader)
                        // Evitiamo di rompere metodi di sistema, colpiamo solo quelli Spotify
                        if (method.declaringClass.name.contains("spotify")) {
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(p: MethodHookParam) { p.result = "ios" }
                            })
                        }
                    }
                }
                XposedBridge.log("SPOOF-CLIENT: Deep Platform Spoof completato")
            }
        }.onFailure {
            XposedBridge.log("SPOOF-CLIENT [ERROR]: DexKit Platform Spoof fallito: ${it.message}")
        }
    }.apply { priority = Thread.MAX_PRIORITY }.start()
}

private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
