package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import kotlin.concurrent.thread

@SuppressLint("DiscouragedPrivateApi")
object Spoof {

    private const val IOS_UA = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"
    private const val IOS_CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3"
    private const val IOS_HARDWARE = "iPhone16,1"
    private const val IOS_SYSTEM = "17.7.2"
    private const val SPOTIFY_SHA = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"

    @Suppress("UNCHECKED_CAST")
    private fun asMethodList(result: Any?): List<MethodData> {
        return when (result) {
            is List<*> -> result as List<MethodData>
            is MethodData -> listOf(result)
            else -> emptyList()
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun init(classLoader: ClassLoader, apkPath: String, moduleApkPath: String) {
        XposedBridge.log("SPOOF [INIT]: Avvio strategia iOS Totale")

        applySystemSpoof()
        applySignatureHook(classLoader)
        applyGlobalConfigSpoof(classLoader)
        applyNativeHttpSpoof(classLoader)
        applyOkHttpSpoof(classLoader)
        applyUltimateProtobufSpoof(classLoader)

        thread {
            runCatching {
                val arch = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
                val libPath = "$moduleApkPath!/lib/$arch/libdexkit.so"
                System.load(libPath)
                DexKitBridge.create(apkPath).use { bridge ->
                    applyDexKitHooks(bridge, classLoader)
                }
            }.onFailure {
                XposedBridge.log("SPOOF [FATAL]: Errore DexKit critico: ${it.message}")
            }
        }
    }

    // --- HOOK DIRETTI ---

    private fun applySystemSpoof() {
        runCatching {
            XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", IOS_HARDWARE)
            XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", IOS_HARDWARE)
            XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", IOS_SYSTEM)
        }
    }

    private fun applyGlobalConfigSpoof(cl: ClassLoader) {
        runCatching {
            val configClass = cl.loadClass("com.spotify.connectivity.ApplicationScopeConfiguration")
            XposedHelpers.findAndHookMethod(configClass, "setDefaultHTTPUserAgent", String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = IOS_UA }
            })
        }
    }

    private fun applyNativeHttpSpoof(cl: ClassLoader) {
        runCatching {
            val httpConnectionImpl = cl.loadClass("com.spotify.core.http.NativeHttpConnection")
            XposedBridge.hookAllMethods(httpConnectionImpl, "send", object : XC_MethodHook() {
                @Suppress("UNCHECKED_CAST")
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args[0] ?: return
                    val oldUrl = XposedHelpers.getObjectField(req, "url") as String
                    XposedHelpers.setObjectField(req, "url", oldUrl.replace("platform=android", "platform=ios").replace("os=android", "os=ios"))
                    val headers = XposedHelpers.getObjectField(req, "headers") as MutableMap<String, String>
                    headers["User-Agent"] = IOS_UA
                    headers["App-Platform"] = "ios"
                    headers["X-Client-Id"] = IOS_CLIENT_ID
                }
            })
        }
    }

    private fun applyOkHttpSpoof(cl: ClassLoader) {
        runCatching {
            val okHttpClientClass = cl.loadClass("okhttp3.OkHttpClient")
            XposedBridge.hookAllMethods(okHttpClientClass, "newCall", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val request = param.args[0] as okhttp3.Request
                    val headers = request.headers.newBuilder()
                        .set("User-Agent", IOS_UA)
                        .set("App-Platform", "ios")
                        .build()
                    param.args[0] = request.newBuilder().headers(headers).build()
                }
            })
        }
    }

    private fun applyUltimateProtobufSpoof(cl: ClassLoader) {
        runCatching {
            val clientInfoClass = cl.loadClass("com.spotify.signup.signup.v2.proto.ClientInfo")
            XposedBridge.hookAllMethods(clientInfoClass, "s", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.getOrNull(0) is String) param.args[0] = "ios"
                }
            })
        }
    }

    private fun applySignatureHook(cl: ClassLoader) {
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", cl)
            XposedBridge.hookAllMethods(pmClass, "getPackageInfo", object : XC_MethodHook() {
                @Suppress("DEPRECATION")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkg = param.args[0] as? String ?: return
                    if (pkg.contains("spotify")) {
                        val info = param.result as? PackageInfo ?: return
                        info.signatures = arrayOf(Signature(hexToBytes(SPOTIFY_SHA)))
                        param.result = info
                    }
                }
            })
        }
    }

    // --- HOOK DINAMICI ---

    private fun applyDexKitHooks(bridge: DexKitBridge, classLoader: ClassLoader) {

        // 1. UA
        runCatching {
            asMethodList(Fingerprints.setUserAgentFingerprint(bridge)).forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = IOS_UA }
                })
            }
        }

        // 2. CLIENT ID
        runCatching {
            asMethodList(Fingerprints.setClientIdFingerprint(bridge)).forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = IOS_CLIENT_ID }
                })
            }
        }

        // 3. MAPS (Sicuro contro 12 risultati e ClassCastException)
        runCatching {
            val mapResults = Fingerprints.loginMapFingerprint(bridge)
            asMethodList(mapResults).forEach { mData ->
                runCatching {
                    XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val arg = param.args?.getOrNull(0)
                            if (arg is MutableMap<*, *>) {
                                @Suppress("UNCHECKED_CAST")
                                val map = arg as MutableMap<String, Any?>
                                if (map.containsKey("platform") || map.containsKey("os")) {
                                    map["platform"] = "ios"
                                    map["App-Platform"] = "ios"
                                    map["os"] = "ios"
                                }
                            }
                        }
                    })
                }
            }
        }.onFailure { XposedBridge.log("SPOOF [DEX]: Errore durante hook Mappe (probabile limite 12)") }

        // 4. PROTO
        runCatching {
            asMethodList(Fingerprints.clientInfoFingerprint(bridge)).forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = "ios" }
                })
            }
        }

        // 5. DEVICE INFO
        runCatching {
            asMethodList(Fingerprints.deviceInfoFingerprint(bridge)).forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = IOS_SYSTEM }
                })
            }
        }

        // 6. HARDWARE
        runCatching {
            Fingerprints.findClientDataMethods(bridge, "getHardwareMachine").forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) { param.result = IOS_HARDWARE }
                })
            }
        }

        XposedBridge.log("SPOOF [DEXKIT]: Tutti gli hook dinamici sono stati processati.")
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}