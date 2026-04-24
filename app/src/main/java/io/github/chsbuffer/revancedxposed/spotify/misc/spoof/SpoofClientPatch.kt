package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import android.content.pm.PackageInfo
import android.content.pm.Signature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.luckypray.dexkit.DexKitBridge

@OptIn(ExperimentalSerializationApi::class)
fun SpoofClient(lpparam: XC_LoadPackage.LoadPackageParam) {
    val classLoader = lpparam.classLoader
    
    // Identità Master V8.8.84 (Sincronizzata con i modelli Protobuf)
    val iosClientId = "58bd3c95768941ea9eb4350aaa033eb3"
    val iosUserAgent = "Spotify/8.8.84 iOS/17.7.2 (iPhone16,1)"
    val iosStaticDeviceId = "2A084F20-1307-3AE0-83C8-AE5CA4AB5CD0"
    val spotifySha = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"
    
    XposedBridge.log("SPOOF-DEBUG: Avvio Modalità IN-MEMORY (Zero Proxy)")

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

    // 2. Hook NativeHttpConnection con TRASFORMAZIONE IN-MEMORY
    runCatching {
        val cl = classLoader
        val httpConnectionImpl = cl.loadClass("com.spotify.core.http.NativeHttpConnection")
        val httpRequest = cl.loadClass("com.spotify.core.http.HttpRequest")
        val urlField = httpRequest.getDeclaredField("url").apply { isAccessible = true }
        
        // Campo che contiene il corpo Protobuf (ByteArray)
        val bodyField = httpRequest.declaredFields.find { it.type == ByteArray::class.java }?.apply { isAccessible = true }

        XposedBridge.hookAllMethods(
            httpConnectionImpl,
            "send",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args[0]
                    val url = (urlField.get(req) as? String) ?: return

                    // --- TRASFORMAZIONE CHIRURGICA DEL TOKEN (Senza Redirect) ---
                    if (url.contains("clienttoken.spotify.com/v1/clienttoken")) {
                        bodyField?.let { field ->
                            val originalBody = field.get(req) as? ByteArray
                            XposedBridge.log("SPOOF-DEBUG: Intercettata richiesta Token in RAM (${originalBody?.size ?: 0} bytes)")
                            
                            // Generazione Payload iOS Master al volo (Master Identity)
                            val iosData = NativeIOSData(userInterfaceIdiom = 0, targetIphoneSimulator = false, hwMachine = "iPhone16,1", systemVersion = "17.7.2")
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
                            XposedBridge.log("SPOOF-DEBUG: Body trasformato in iOS Master -> ${iosBody.size} bytes")
                        }
                    }

                    // --- SPOOFING HEADER (Login5 e spclient) ---
                    if (url.contains("spotify.com") || url.contains("scdn.co") || url.contains("spclient")) {
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

    // 3. Targeted Platform Spoof (DexKit)
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
                XposedBridge.log("SPOOF-DEBUG: DexKit Platform Spoof attivo")
            }
        }.onFailure {
            XposedBridge.log("SPOOF-DEBUG: DexKit fallito: ${it.message}")
        }
    }.apply { priority = Thread.MAX_PRIORITY }.start()
}

private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
