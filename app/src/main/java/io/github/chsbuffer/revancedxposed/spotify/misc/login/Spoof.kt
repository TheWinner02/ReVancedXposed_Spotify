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
        XposedBridge.log("SPOOF [INIT]: Avvio Strategia Avanzata")

        // 1. Hook di Sistema Immediati
        applySystemSpoof()
        applySignatureHook(classLoader)
        applyNativeHttpSpoof(classLoader)

        // 2. Task in background per DexKit e Deep Spoof
        thread {
            Thread.sleep(2000) // Fondamentale per lasciare che l'app si calmi
            runCatching {
                val arch = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
                val libPath = "$moduleApkPath!/lib/$arch/libdexkit.so"
                System.load(libPath)

                DexKitBridge.create(apkPath).use { bridge ->
                    applyDexKitAndDeepHooks(bridge, classLoader)
                }
            }.onFailure {
                XposedBridge.log("SPOOF [FATAL]: Errore durante l'inizializzazione core: ${it.message}")
            }
        }
    }

    private fun applySystemSpoof() {
        runCatching {
            XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", IOS_HARDWARE)
            XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "apple")
            XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", IOS_SYSTEM)
        }
    }

    private fun applyNativeHttpSpoof(cl: ClassLoader) {
        runCatching {
            val httpConnectionImpl = cl.loadClass("com.spotify.core.http.NativeHttpConnection")
            XposedBridge.hookAllMethods(httpConnectionImpl, "send", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args[0] ?: return
                    runCatching {
                        val url = XposedHelpers.getObjectField(req, "url") as String
                        // Forziamo URL iOS
                        if (url.contains("platform=android")) {
                            XposedHelpers.setObjectField(req, "url", url.replace("platform=android", "platform=ios"))
                        }

                        // Hook degli Header (Riflessione profonda per trovare la mappa)
                        val headersField = req.javaClass.declaredFields.find { it.type == Map::class.java || it.type.name.contains("headers", true) }
                        headersField?.let {
                            it.isAccessible = true
                            val map = it.get(req) as? MutableMap<String, String>
                            map?.let { m ->
                                m["User-Agent"] = IOS_UA
                                m["App-Platform"] = "ios"
                                m["X-Client-Id"] = IOS_CLIENT_ID
                            }
                        }
                    }
                }
            })
        }
    }

    private fun applyDexKitAndDeepHooks(bridge: DexKitBridge, cl: ClassLoader) {
        XposedBridge.log("SPOOF [DEX]: Inizio Scansione")

        // Hook per Mappe e Protobuf Dinamici (Il cuore del successo)
        runCatching {
            val mapMethods = asMethodList(Fingerprints.loginMapFingerprint(bridge))
            mapMethods.forEach { mData ->
                runCatching {
                    XposedBridge.hookMethod(mData.getMethodInstance(cl), object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val arg = param.args?.getOrNull(0) ?: return

                            // A: Gestione Mappe (Classico)
                            if (arg is MutableMap<*, *>) {
                                @Suppress("UNCHECKED_CAST")
                                val map = arg as MutableMap<String, Any?>
                                if (map.containsKey("platform") || map.containsKey("App-Platform")) {
                                    map["platform"] = "ios"
                                    map["App-Platform"] = "ios"
                                    map["os"] = "ios"
                                }
                            }
                            // B: Gestione Protobuf (Riflessione sui campi interni)
                            else if (arg.javaClass.name.startsWith("com.spotify")) {
                                arg.javaClass.declaredFields.forEach { field ->
                                    if (field.name == "platform_" || field.name == "os_") {
                                        runCatching {
                                            field.isAccessible = true
                                            if (field.type == String::class.java) field.set(arg, "ios")
                                            else if (field.type == Int::class.javaPrimitiveType) field.set(arg, 1)
                                        }
                                    }
                                }
                            }
                        }
                    })
                }
            }
        }

        // Hook per ClientID e UA (DexKit)
        listOf(
            Fingerprints.setClientIdFingerprint(bridge) to IOS_CLIENT_ID,
            Fingerprints.setUserAgentFingerprint(bridge) to IOS_UA
        ).forEach { (res, value) ->
            asMethodList(res).forEach { m ->
                runCatching {
                    XposedBridge.hookMethod(m.getMethodInstance(cl), object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) { p.args[0] = value }
                    })
                }
            }
        }

        XposedBridge.log("SPOOF [DEX]: Scansione completata.")
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

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}