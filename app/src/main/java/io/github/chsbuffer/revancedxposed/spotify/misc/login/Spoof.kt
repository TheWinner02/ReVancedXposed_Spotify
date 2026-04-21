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

    // Usiamo lo User-Agent ESATTO che hai trovato nel codice di ReVanced
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
        XposedBridge.log("SPOOF [INIT]: Inizializzazione modulo... Identità iOS impostata.")

        applySystemSpoof()
        applySignatureHook(classLoader)
        applyGlobalConfigSpoof(classLoader) // <--- NUOVO: Quello che hai trovato su JADX
        applyNativeHttpSpoof(classLoader)
        applyOkHttpSpoof(classLoader)

        thread {
            runCatching {
                val arch = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
                val libPath = "$moduleApkPath!/lib/$arch/libdexkit.so"
                System.load(libPath)
                DexKitBridge.create(apkPath).use { bridge ->
                    applyDexKitHooks(bridge, classLoader)
                }
            }.onFailure { XposedBridge.log("SPOOF [FATAL]: Errore DexKit: ${it.message}") }
        }
    }

    private fun applySystemSpoof() {
        runCatching {
            XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", IOS_HARDWARE)
            XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", IOS_HARDWARE)
            XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", IOS_SYSTEM)
        }
    }

    // Basato sulla tua scoperta in com.spotify.connectivity.ApplicationScopeConfiguration
    private fun applyGlobalConfigSpoof(cl: ClassLoader) {
        runCatching {
            val configClass = cl.loadClass("com.spotify.connectivity.ApplicationScopeConfiguration")
            XposedHelpers.findAndHookMethod(configClass, "setDefaultHTTPUserAgent", String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = IOS_UA
                    XposedBridge.log("SPOOF [CONFIG]: Forzato User-Agent globale -> $IOS_UA")
                }
            })
        }
    }

    private fun applyNativeHttpSpoof(cl: ClassLoader) {
        runCatching {
            val httpConnectionImpl = cl.loadClass("com.spotify.core.http.NativeHttpConnection")
            val httpRequest = cl.loadClass("com.spotify.core.http.HttpRequest")
            val urlField = httpRequest.getDeclaredField("url").apply { isAccessible = true }
            val headersField = httpRequest.declaredFields.find { it.type == Map::class.java || it.type.name.contains("Headers", true) }?.apply { isAccessible = true }

            XposedBridge.hookAllMethods(httpConnectionImpl, "send", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args[0] ?: return
                    val url = urlField.get(req) as? String ?: return

                    headersField?.let { field ->
                        val headers = field.get(req)
                        if (headers is MutableMap<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val map = headers as MutableMap<String, String>
                            map["User-Agent"] = IOS_UA
                            map["App-Platform"] = "ios"
                            map["X-Client-Id"] = IOS_CLIENT_ID

                            if (url.contains("clienttoken") || url.contains("login")) {
                                map["platform"] = "ios"
                            }
                        }
                    }
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
                    val headersBuilder = request.headers.newBuilder()
                    headersBuilder.set("User-Agent", IOS_UA)
                    headersBuilder.set("App-Platform", "ios")

                    param.args[0] = request.newBuilder().headers(headersBuilder.build()).build()
                }
            })
        }
    }

    private fun applySignatureHook(cl: ClassLoader) {
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", cl)
            XposedBridge.hookAllMethods(pmClass, "getPackageInfo", object : XC_MethodHook() {
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

    private fun applyDexKitHooks(bridge: DexKitBridge, classLoader: ClassLoader) {
        runCatching {
            val mapMethods = asMethodList(Fingerprints.loginMapFingerprint(bridge))
            mapMethods.forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val arg = param.args?.getOrNull(0) ?: return
                        if (arg is MutableMap<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val map = arg as MutableMap<String, Any?>
                            map["platform"] = "ios"
                            map["App-Platform"] = "ios"
                        } else if (arg.javaClass.name.startsWith("com.spotify")) {
                            arg.javaClass.declaredFields.forEach { field ->
                                if (field.name == "platform_") {
                                    field.isAccessible = true
                                    field.set(arg, "ios")
                                }
                            }
                        }
                    }
                })
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}