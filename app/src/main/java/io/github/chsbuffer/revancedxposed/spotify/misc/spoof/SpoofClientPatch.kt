package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import io.github.chsbuffer.revancedxposed.spotify.misc.login.Fingerprints
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import kotlin.concurrent.thread

private var listener: RequestListener? = null

fun SpotifyHook.SpoofClient() {
    val port = 4345
    val iosClientId = "58bd3c95768941ea9eb4350aaa033eb3"
    val iosUserAgent = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"
    val spotifySha = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"
    
    // 1. Hook di Sistema Immediati (Build Spoof)
    runCatching {
        XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", "iPhone16,1")
        XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Apple")
        XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "apple")
        XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", "17.7.2")
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
        }.onFailure {
            XposedBridge.log("SpoofClient: Failed to launch listener -> ${it.message}")
        }
    }

    // 4. Hook ApplicationScopeConfiguration
    runCatching {
        val configClass = classLoader.loadClass("com.spotify.connectivity.ApplicationScopeConfiguration")
        
        XposedHelpers.findAndHookMethod(configClass, "setClientId", String::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                XposedBridge.log("SPOOF CLIENT: setClientId($iosClientId)")
                param.args[0] = iosClientId
            }
        })

        XposedHelpers.findAndHookMethod(configClass, "setDefaultHTTPUserAgent", String::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                XposedBridge.log("SPOOF CLIENT: setDefaultHTTPUserAgent($iosUserAgent)")
                param.args[0] = iosUserAgent
            }
        })
    }.onFailure {
        XposedBridge.log("SpoofClient: ApplicationScopeConfiguration hooks error -> ${it.message}")
    }

    // 5. Hook NativeHttpConnection
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

                    if (url == "https://clienttoken.spotify.com/v1/clienttoken") {
                        XposedBridge.log("SPOOF CLIENT: Redirecting $url")
                        urlField.set(req, "http://127.0.0.1:$port/v1/clienttoken")
                    } else if (url.contains("platform=android")) {
                        urlField.set(req, url.replace("platform=android", "platform=ios"))
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
                            }
                        }
                    }
                }
            }
        )
    }.onFailure {
        XposedBridge.log("SpoofClient: NativeHttpConnection hook error -> ${it.message}")
    }

    // 6. Deep Spoof con DexKit
    thread {
        Thread.sleep(2000)
        runCatching {
            val apkPath = lpparam.appInfo.sourceDir
            DexKitBridge.create(apkPath).use { bridge ->
                applyDexKitDeepHooks(bridge, classLoader, iosClientId, iosUserAgent)
            }
        }.onFailure {
            XposedBridge.log("SPOOF [FATAL]: Errore durante l'inizializzazione DexKit: ${it.message}")
        }
    }
}

private fun applyDexKitDeepHooks(bridge: DexKitBridge, cl: ClassLoader, clientId: String, ua: String) {
    XposedBridge.log("SPOOF [DEX]: Inizio Scansione")

    runCatching {
        val mapMethods = asMethodList(Fingerprints.loginMapFingerprint(bridge))
        mapMethods.forEach { mData ->
            runCatching {
                XposedBridge.hookMethod(mData.getMethodInstance(cl), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val arg = param.args?.getOrNull(0) ?: return

                        if (arg is MutableMap<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val map = arg as MutableMap<String, Any?>
                            if (map.containsKey("platform") || map.containsKey("App-Platform")) {
                                map["platform"] = "ios"
                                map["App-Platform"] = "ios"
                                map["os"] = "ios"
                            }
                        }
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

    listOf(
        Fingerprints.setClientIdFingerprint(bridge) to clientId,
        Fingerprints.setUserAgentFingerprint(bridge) to ua
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

private fun asMethodList(result: Any?): List<MethodData> {
    return when (result) {
        is List<*> -> @Suppress("UNCHECKED_CAST") (result as List<MethodData>)
        is MethodData -> listOf(result)
        else -> emptyList()
    }
}

private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
