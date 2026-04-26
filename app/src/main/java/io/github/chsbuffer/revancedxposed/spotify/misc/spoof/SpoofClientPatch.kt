package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.MASTER_CLIENT_ID
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.MASTER_DEVICE_ID
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.MASTER_USER_AGENT
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.PLATFORM_IOS_TARGET
import io.github.chsbuffer.revancedxposed.spotify.misc.spoof.SpoofFingerprints.SPOTIFY_ORIGINAL_SHA
import kotlinx.serialization.ExperimentalSerializationApi
import org.luckypray.dexkit.DexKitBridge
import java.security.cert.X509Certificate

private var isCalendarActive = false

@OptIn(ExperimentalSerializationApi::class)
fun SpoofClient(lpparam: XC_LoadPackage.LoadPackageParam) {
    val classLoader = lpparam.classLoader
    
    XposedBridge.log("SPOOF-DEBUG: Avvio Modalità iOS-ZOMBIE v5.4 (Anti-Protection Test)")

    // 1. SSL PINNING BYPASS (Anti-BlackScreen)
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

    // 2. HARDWARE SPOOF (Deep Identity)
    runCatching {
        XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", "iPhone16,1")
        XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Apple")
        XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "Apple")
        XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", "iPhone")
        XposedHelpers.setStaticObjectField(Build::class.java, "PRODUCT", "iPhone16,1")
    }

    // 3. STEALTH INTEGRITY KILL (DexKit)
    runCatching { System.loadLibrary("dexkit") }
    Thread {
        runCatching {
            val apkPath = lpparam.appInfo.sourceDir ?: return@Thread
            DexKitBridge.create(apkPath).use { bridge ->
                XposedBridge.log("SPOOF-DEBUG: Scansione Integrity Guard...")
                
                // Target: Metodi di verifica interna che potrebbero bloccare l'app
                bridge.findMethod {
                    matcher { 
                        returnType = "Z"
                        usingStrings("com.spotify.music", "integrity")
                    }
                }.forEach { mData ->
                    runCatching {
                        val method = mData.getMethodInstance(classLoader)
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(p: MethodHookParam) { 
                                p.result = true 
                                XposedBridge.log("SPOOF-DEBUG: Integrity Guard BYPASSED -> ${method.name}")
                            }
                        })
                    }
                }
                XposedBridge.log("SPOOF-DEBUG: Scansione COMPLETATA")
            }
        }
    }.apply { priority = Thread.MAX_PRIORITY }.start()

    // 4. Hook NativeHttpConnection (Header Spoof - NO BODY TRANSFORMATION per test)
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

                    if (url.contains("spotify.com") || url.contains("spclient")) {
                        runCatching {
                            val headersField = req.javaClass.declaredFields.find { it.type == Map::class.java }
                            headersField?.let {
                                it.isAccessible = true
                                @Suppress("UNCHECKED_CAST")
                                val map = it.get(req) as? MutableMap<String, String>
                                map?.let { m ->
                                    m["User-Agent"] = MASTER_USER_AGENT
                                    m["App-Platform"] = "ios"
                                    m["X-Client-Id"] = MASTER_CLIENT_ID
                                    m["X-Spotify-Device-Id"] = MASTER_DEVICE_ID
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    // 5. CALENDAR STABILITY
    runCatching {
        XposedHelpers.findAndHookMethod(
            "java.util.Calendar",
            classLoader,
            "get",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isCalendarActive) return
                    isCalendarActive = true
                    try {
                        val field = param.args[0] as Int
                        if (field == 1) param.result = 2024
                    } finally {
                        isCalendarActive = false
                    }
                }
            }
        )
    }

    // 6. Signature Spoof Deep
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
}

private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
