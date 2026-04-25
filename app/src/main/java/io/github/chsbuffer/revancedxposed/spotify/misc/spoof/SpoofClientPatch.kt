package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.luckypray.dexkit.DexKitBridge
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

@OptIn(ExperimentalSerializationApi::class)
fun SpoofClient(lpparam: XC_LoadPackage.LoadPackageParam) {
    val classLoader = lpparam.classLoader
    
    // Identità Master V8.8.84
    val iosClientId = "58bd3c95768941ea9eb4350aaa033eb3"
    val iosUserAgent = "Spotify/8.8.84 iOS/17.7.2 (iPhone16,1)"
    val iosStaticDeviceId = "2A084F20-1307-3AE0-83C8-AE5CA4AB5CD0"
    
    XposedBridge.log("SPOOF-DEBUG: Avvio Modalità iOS-ZOMBIE (Direct RAM + SSL Bypass)")

    // 1. SSL PINNING BYPASS (Elimina la schermata nera di protezione)
    runCatching {
        val trustManagerClass = XposedHelpers.findClass("com.android.org.conscrypt.TrustManagerImpl", classLoader)
        XposedHelpers.findAndHookMethod(
            trustManagerClass,
            "checkServerTrusted",
            Array<X509Certificate>::class.java,
            String::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
            }
        )
    }

    // 2. Bypass Device ID (Anti-Samsung Knox)
    runCatching {
        XposedHelpers.findAndHookMethod(
            "android.provider.Settings\$Secure",
            classLoader,
            "getString",
            android.content.ContentResolver::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args[1] == "android_id") {
                        param.result = "2A084F2013073AE0"
                    }
                }
            }
        )
    }

    // 3. Hook NativeHttpConnection con TRASFORMAZIONE IN-MEMORY
    runCatching {
        val cl = classLoader
        val httpConnectionImpl = cl.loadClass("com.spotify.core.http.NativeHttpConnection")
        val httpRequest = cl.loadClass("com.spotify.core.http.HttpRequest")
        val urlField = httpRequest.getDeclaredField("url").apply { isAccessible = true }
        val bodyField = httpRequest.declaredFields.find { it.type == ByteArray::class.java }?.apply { isAccessible = true }

        XposedBridge.hookAllMethods(
            httpConnectionImpl,
            "send",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args[0]
                    val url = (urlField.get(req) as? String) ?: return

                    // TRASFORMAZIONE TOKEN (In-Memory, Connessione Diretta HTTPS)
                    if (url.contains("clienttoken.spotify.com/v1/clienttoken")) {
                        bodyField?.let { field ->
                            val originalBody = field.get(req) as? ByteArray
                            XposedBridge.log("SPOOF-DEBUG: Trasformazione RAM (${originalBody?.size} bytes -> iOS Master)")
                            
                            val iosData = NativeIOSData(userInterfaceIdiom = 0, targetIphoneSimulator = false, hwMachine = "iPhone16,1", systemVersion = "17.7.2", simulatorModelIdentifier = "")
                            val transformedRequest = ClientTokenRequest(
                                requestType = ClientTokenRequestType.REQUEST_CLIENT_DATA_REQUEST,
                                clientData = ClientDataRequest(
                                    clientVersion = "iphone-8.8.84.502",
                                    clientId = iosClientId,
                                    connectivitySdkData = ConnectivitySdkData(deviceId = iosStaticDeviceId, platformSpecificData = PlatformSpecificData(ios = iosData))
                                )
                            )
                            
                            val iosBody = ProtoBuf.encodeToByteArray(transformedRequest)
                            field.set(req, iosBody)
                        }
                    }

                    // SPOOFING HEADER (Identità Protetta)
                    if (url.contains("spotify.com") || url.contains("spclient")) {
                        runCatching {
                            val headersField = req.javaClass.declaredFields.find { it.type == Map::class.java }
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

    // 4. DexKit Platform Spoof (Sblocco Configurazioni)
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
                        if (method.declaringClass.name.contains("spotify")) {
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(p: MethodHookParam) { p.result = "ios" }
                            })
                        }
                    }
                }
                XposedBridge.log("SPOOF-DEBUG: DexKit Platform Spoof completato")
            }
        }.onFailure {
            XposedBridge.log("SPOOF-DEBUG [ERROR]: DexKit fallito: ${it.message}")
        }
    }.apply { priority = Thread.MAX_PRIORITY }.start()
}

private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
