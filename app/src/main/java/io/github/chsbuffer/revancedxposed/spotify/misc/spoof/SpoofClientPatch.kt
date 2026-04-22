package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.parameters
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private var listener: RequestListener? = null
private val executor = Executors.newSingleThreadScheduledExecutor()

fun SpotifyHook.SpoofClient() {
    val port = 4345
    val iosClientId = "58bd3c95768941ea9eb4350aaa033eb3"
    val iosUserAgent = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"
    val spotifySha = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"
    
    XposedBridge.log("SPOOF-CLIENT: Inizializzazione logica iOS Spoofing")

    // 1. Hook di Sistema Immediati (Build Spoof)
    runCatching {
        XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", "iPhone")
        XposedHelpers.setStaticObjectField(Build::class.java, "PRODUCT", "iPhone16,1")
        XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", "iPhone16,1")
        XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Apple")
        XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "apple")
        XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", "17.7.2")
        System.setProperty("http.agent", iosUserAgent)
        XposedBridge.log("SPOOF-CLIENT: Build properties modificate in iOS")
    }.onFailure {
        XposedBridge.log("SPOOF-CLIENT: Errore durante Build spoof: ${it.message}")
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
                    XposedBridge.log("SPOOF-CLIENT: Signature spoof applicata per $pkg")
                }
            }
        })
    }.onFailure {
        XposedBridge.log("SPOOF-CLIENT: Errore durante Signature spoof hook: ${it.message}")
    }

    // 3. Proxy Listener
    if (listener == null) {
        runCatching {
            listener = RequestListener(port)
            XposedBridge.log("SPOOF-CLIENT: RequestListener avviato sulla porta $port")
        }.onFailure {
            XposedBridge.log("SPOOF-CLIENT: Failed to launch listener -> ${it.message}")
        }
    }

    // 4. Hook ApplicationScopeConfiguration
    runCatching {
        val configClass = classLoader.loadClass("com.spotify.connectivity.ApplicationScopeConfiguration")
        
        XposedHelpers.findAndHookMethod(configClass, "setClientId", String::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                XposedBridge.log("SPOOF-CLIENT: setClientId forzato a $iosClientId")
                param.args[0] = iosClientId
            }
        })

        XposedHelpers.findAndHookMethod(configClass, "setDefaultHTTPUserAgent", String::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                XposedBridge.log("SPOOF-CLIENT: setDefaultHTTPUserAgent forzato a $iosUserAgent")
                param.args[0] = iosUserAgent
            }
        })
        XposedBridge.log("SPOOF-CLIENT: ApplicationScopeConfiguration hookati con successo")
    }.onFailure {
        XposedBridge.log("SPOOF-CLIENT: ApplicationScopeConfiguration hooks error -> ${it.message}")
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
                        XposedBridge.log("SPOOF-CLIENT: Redirecting $url -> http://127.0.0.1:$port/v1/clienttoken")
                        urlField.set(req, "http://127.0.0.1:$port/v1/clienttoken")
                    } else if (url.contains("platform=android") || url.contains("device=android")) {
                        val newUrl = url.replace("platform=android", "platform=ios")
                                        .replace("device=android", "device=ios")
                        XposedBridge.log("SPOOF-CLIENT: Patching URL platform -> $newUrl")
                        urlField.set(req, newUrl)
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
        XposedBridge.log("SPOOF-CLIENT: NativeHttpConnection hookato con successo")
    }.onFailure {
        XposedBridge.log("SPOOF-CLIENT: NativeHttpConnection hook error -> ${it.message}")
    }

    // 6. Deep Spoof con DexKit
    executor.schedule({
        XposedBridge.log("SPOOF-CLIENT: Avvio scansione DexKit in background")
        runCatching {
            val apkPath = lpparam.appInfo.sourceDir
            DexKitBridge.create(apkPath).use { bridge ->
                // Passiamo anche la porta qui
                applyDexKitDeepHooks(bridge, classLoader, iosClientId, iosUserAgent, port)
            }
        }.onFailure {
            XposedBridge.log("SPOOF-CLIENT [FATAL]: Errore durante l'inizializzazione DexKit: ${it.message}")
        }
    }, 5, TimeUnit.SECONDS)
}

private fun applyDexKitDeepHooks(bridge: DexKitBridge, cl: ClassLoader, clientId: String, ua: String, port: Int) {
    XposedBridge.log("SPOOF-CLIENT [DEX]: Inizio Scansione fingerprints")

    // 1. Forza il ritorno dei getter "android" -> "ios"
    runCatching {
        val methods1 = bridge.findMethod {
            matcher {
                returnType = "java.lang.String"
                usingStrings("android")
            }
        }
        methods1.forEach { mData ->
            XposedBridge.hookMethod(mData.getMethodInstance(cl), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = "ios"
                }
            })
        }
    }

    // 2. Forza gli header nelle mappe di login
    runCatching {
        val methods = bridge.findMethod {
            matcher {
                parameters("Ljava/util/Map;")
                usingStrings("App-Platform")
            }
        }
        methods.forEach { mData ->
            XposedBridge.hookMethod(mData.getMethodInstance(cl), object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val map = p.args[0] as? MutableMap<String, String> ?: return
                    map["App-Platform"] = "ios"
                    map["User-Agent"] = ua
                    map["X-Client-Id"] = clientId
                }
            })
        }
    }

    runCatching {
        // Cerca i metodi che restituiscono "android" e falli restituire "ios"
        val methods1 = bridge.findMethod {
            matcher {
                returnType = "java.lang.String"
                usingStrings("android")
            }
        }
        methods1.forEach { mData ->
            XposedBridge.hookMethod(mData.getMethodInstance(cl), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = "ios"
                }
            })
        }
    }

    // Hook per Mappe e Protobuf Dinamici
    runCatching {
        val methods = bridge.findMethod {
            matcher {
                parameters("Ljava/util/Map;")
                returnType = "V"
                modifiers = java.lang.reflect.Modifier.PUBLIC
                usingStrings("App-Platform")
            }
        }

        XposedBridge.log("SPOOF-CLIENT [DEX]: Trovati ${methods.size} metodi per loginMap")

        methods.forEach { mData ->
            runCatching {
                XposedBridge.hookMethod(mData.getMethodInstance(cl), object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val originalUrl = p.args[0] as String
                        if (originalUrl.contains("127.0.0.1")) return // Non intercettare se è già il proxy

                        if (originalUrl.contains("spotify.com/2") || originalUrl.contains("spotify.com/3") || originalUrl.contains("clienttoken")) {
                            p.args[0] = "http://127.0.0.1:$port/v1/clienttoken"
                            XposedBridge.log("SPOOF-CLIENT: REDIRECT ESEGUITO -> ${p.args[0]}")
                        }
                    }
                })
            }
        }
    }.onFailure {
        XposedBridge.log("SPOOF-CLIENT [DEX]: Errore durante scansione loginMap: ${it.message}")
    }

    // Hook per ClientID e UA via DexKit (Scansione manuale per gestire duplicati)
    listOf(
        "setClientId" to clientId,
        "setDefaultHTTPUserAgent" to ua
    ).forEach { (methodName, value) ->
        runCatching {
            val methods = bridge.findMethod {
                matcher {
                    name = methodName
                    parameters("Ljava/lang/String;")
                }
            }
            
            XposedBridge.log("SPOOF-CLIENT [DEX]: Trovati ${methods.size} metodi per $methodName")
            
            methods.forEach { mData ->
                runCatching {
                    XposedBridge.hookMethod(mData.getMethodInstance(cl), object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) { 
                            p.args[0] = value 
                        }
                    })
                }
            }
        }.onFailure {
            XposedBridge.log("SPOOF-CLIENT [DEX]: Errore durante hook DexKit per $methodName: ${it.message}")
        }
    }

    XposedBridge.log("SPOOF-CLIENT [DEX]: Scansione completata.")
}

private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
