package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import android.content.pm.PackageInfo
import android.content.pm.Signature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.INTEGRITY_PROTECTION_METHOD
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.INTEGRITY_PROTECTION_PACKAGE
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.MASTER_CLIENT_ID
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.MASTER_DEVICE_ID
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.MASTER_USER_AGENT
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.MASTER_VERSION
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.PLATFORM_IDENTIFIER_STRING
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.PLATFORM_IOS_TARGET
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.SPOTIFY_ORIGINAL_SHA
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.luckypray.dexkit.DexKitBridge
import java.security.cert.X509Certificate

@OptIn(ExperimentalSerializationApi::class)
fun SpoofClient(lpparam: XC_LoadPackage.LoadPackageParam) {
    val classLoader = lpparam.classLoader
    
    XposedBridge.log("SPOOF-DEBUG: Avvio Modalità iOS-ZOMBIE v4.7 (Modular + Response Hook)")

    // 1. SSL PINNING BYPASS
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

    // 2. Signature Spoof
    runCatching {
        val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
        XposedBridge.hookAllMethods(pmClass, "getPackageInfo", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val pkg = param.args[0] as? String ?: return
                if (pkg.contains("spotify")) {
                    val info = param.result as? PackageInfo ?: return
                    @Suppress("DEPRECATION")
                    info.signatures = arrayOf(Signature(hexToBytes(SPOTIFY_ORIGINAL_SHA)))
                    param.result = info
                }
            }
        })
    }

    // 3. KILL INTEGRITY VERIFICATION (Fingerprint System)
    Thread {
        runCatching {
            val apkPath = lpparam.appInfo.sourceDir ?: return@Thread
            DexKitBridge.create(apkPath).use { bridge ->
                bridge.findMethod {
                    matcher { returnType = "V" }
                }.forEach { mData ->
                    if (mData.methodName == INTEGRITY_PROTECTION_METHOD && 
                        mData.descriptor.contains(INTEGRITY_PROTECTION_PACKAGE)) {
                         runCatching {
                            val method = mData.getMethodInstance(classLoader)
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(p: MethodHookParam) { 
                                    p.result = null 
                                    XposedBridge.log("SPOOF-DEBUG: Integrity Protection BYPASSED")
                                }
                            })
                        }
                    }
                }

                bridge.findMethod {
                    matcher {
                        returnType = "java.lang.String"
                        usingStrings(PLATFORM_IDENTIFIER_STRING)
                    }
                }.forEach { mData ->
                    runCatching {
                        val method = mData.getMethodInstance(classLoader)
                        if (method.declaringClass.name.contains("spotify")) {
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(p: MethodHookParam) { p.result = PLATFORM_IOS_TARGET }
                            })
                        }
                    }
                }
            }
        }
    }.apply { priority = Thread.MAX_PRIORITY }.start()

    // 4. Hook NativeHttpConnection (RAM Transform + Response Decoding)
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

                    if (url.contains("clienttoken.spotify.com/v1/clienttoken")) {
                        bodyField?.let { field ->
                            val originalBody = field.get(req) as? ByteArray
                            XposedBridge.log("SPOOF-DEBUG: RAM Intercept Token Request (${originalBody?.size} bytes)")
                            
                            val iosData = NativeIOSData(userInterfaceIdiom = 0, targetIphoneSimulator = false, hwMachine = "iPhone16,1", systemVersion = "17.7.2", simulatorModelIdentifier = "")
                            val transformedRequest = ClientTokenRequest(
                                requestType = ClientTokenRequestType.REQUEST_CLIENT_DATA_REQUEST,
                                clientData = ClientDataRequest(
                                    clientVersion = MASTER_VERSION,
                                    clientId = MASTER_CLIENT_ID,
                                    connectivitySdkData = ConnectivitySdkData(deviceId = MASTER_DEVICE_ID, platformSpecificData = PlatformSpecificData(ios = iosData))
                                )
                            )
                            val iosBody = ProtoBuf.encodeToByteArray(transformedRequest)
                            field.set(req, iosBody)
                        }
                    }

                    if (url.contains("spotify.com") || url.contains("spclient")) {
                        runCatching {
                            val headersField = req.javaClass.declaredFields.find { it.type == Map::class.java }
                            headersField?.let {
                                it.isAccessible = true
                                @Suppress("UNCHECKED_CAST")
                                val map = it.get(req) as? MutableMap<String, String>
                                map?.let { m ->
                                    m["User-Agent"] = MASTER_USER_AGENT
                                    m["App-Platform"] = PLATFORM_IOS_TARGET
                                    m["X-Client-Id"] = MASTER_CLIENT_ID
                                    m["X-Spotify-Device-Id"] = MASTER_DEVICE_ID
                                }
                            }
                        }
                    }
                }
            }
        )

        // Intercettiamo il RITORNO del token per completare il debug a 4 stadi in RAM
        XposedBridge.hookAllMethods(
            httpConnectionImpl,
            "onBytesAvailable",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val bytes = param.args[0] as? ByteArray ?: return
                    runCatching {
                        val resp = ProtoBuf.decodeFromByteArray<ClientTokenResponse>(bytes)
                        if (resp.responseType == ClientTokenResponseType.RESPONSE_CHALLENGES_RESPONSE) {
                            XposedBridge.log("SPOOF-DEBUG: RITORNO RAM -> CHALLENGE rilevato (Captcha richiesto)")
                        } else if (resp.responseType == ClientTokenResponseType.RESPONSE_GRANTED_TOKEN_RESPONSE) {
                            val expires = resp.grantedToken?.expiresAfterSeconds ?: 0
                            XposedBridge.log("SPOOF-DEBUG: RITORNO RAM -> TOKEN ottenuto! Scadenza: ${expires}s")
                        }
                    }
                }
            }
        )
    }
}

private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
