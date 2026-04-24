package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import org.luckypray.dexkit.DexKitBridge
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private var listener: RequestListener? = null
private val executor = Executors.newSingleThreadScheduledExecutor()

fun SpotifyHook.SpoofClient() {
    val port = 4345
    val iosClientId = "58bd3c95768941ea9eb4350aaa033eb3"
    val iosUserAgent = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"
    val iosStaticDeviceId = "2A084F20-1307-3AE0-83C8-AE5CA4AB5CD0"
    val spotifySha = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"
    
    XposedBridge.log("SPOOF-CLIENT: Inizializzazione Raffinata iOS Spoofing")

    // 1. Hook di Sistema (Semplificato per evitare blocchi Samsung/PlayProtect)
    runCatching {
        System.setProperty("http.agent", iosUserAgent)
        // Modifichiamo solo i parametri minimi necessari per l'identità core
        XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Apple")
        XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "apple")
        XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", "17.7.2")
    }

    // 2. Signature Spoof (Rimane invariato, necessario per login)
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

    // 3. Proxy Listener
    if (listener == null) {
        runCatching {
            listener = RequestListener(port)
            XposedBridge.log("SPOOF-CLIENT: RequestListener attivo su $port")
        }
    }

    // 4. Hook NativeHttpConnection (Surgical Mode)
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

                    // 1. Prevenzione Loop e Chiamate Esterne al Proxy
                    if (url.contains("127.0.0.1")) return

                    // 2. Blocco Chirurgico Pubblicità e Tracking
                    // Proteggiamo domini Google e Samsung necessari per la stabilità del sistema
                    val isAdOrTracking = (url.contains("/ads/", true) || 
                                       url.contains("/ad-logic/", true) ||
                                       url.contains("analytics.spotify.com", true) ||
                                       url.contains("tracking.spotify.com", true) ||
                                       url.contains("log.spotify.com", true) ||
                                       url.contains("crashdump.spotify.com", true)) &&
                                       !url.contains("google", true) &&
                                       !url.contains("samsung", true)

                    if (isAdOrTracking) {
                        XposedBridge.log("SPOOF-CLIENT [NHB]: Blocked -> $url")
                        param.result = null
                        return
                    }

                    // 3. REDIRECT CHIRURGICO PER TOKEN
                    // Intercettiamo SOLO l'endpoint del token per evitare il "buco nero"
                    if (url.contains("clienttoken.spotify.com/v1/clienttoken")) {
                        val proxyUrl = "http://127.0.0.1:$port/v1/clienttoken"
                        urlField.set(req, proxyUrl)
                        XposedBridge.log("SPOOF-CLIENT: Redirect Chirurgico Token -> $url")
                        return
                    }

                    // 4. SPOOFING GLOBALE (Parametri URL e Header)
                    // Se la richiesta va a Spotify, forziamo l'identità iOS ovunque SENZA passare dal proxy
                    if (url.contains("spotify.com") || url.contains("scdn.co") || url.contains("spclient")) {
                        
                        // Sostituzione parametri nella Query String
                        if (url.contains("android")) {
                            val newUrl = url.replace("platform=android", "platform=ios")
                                            .replace("device=android", "device=ios")
                                            .replace("os=android", "os=ios")
                            if (newUrl != url) {
                                urlField.set(req, newUrl)
                            }
                        }
                        
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
                                    
                                    // Sincronizzazione Device ID con Identità iOS Statica
                                    m["X-Spotify-Device-Id"] = iosStaticDeviceId
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    // 5. Deep Spoof DexKit (Ridotto per evitare crash/blocchi boot)
    executor.schedule({
        runCatching {
            val apkPath = lpparam.appInfo.sourceDir
            DexKitBridge.create(apkPath).use { bridge ->
                // Hookiamo solo i metodi che ritornano "android" -> "ios"
                val methods = bridge.findMethod {
                    matcher {
                        returnType = "java.lang.String"
                        usingStrings("android")
                    }
                }
                methods.forEach { mData ->
                    runCatching {
                        XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                            override fun beforeHookedMethod(p: MethodHookParam) { p.result = "ios" }
                        })
                    }
                }
            }
        }
    }, 10, TimeUnit.SECONDS) // Delay aumentato per stabilità
}

private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
