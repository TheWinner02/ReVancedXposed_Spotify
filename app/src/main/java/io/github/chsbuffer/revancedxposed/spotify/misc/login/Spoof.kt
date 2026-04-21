package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

@SuppressLint("DiscouragedPrivateApi")
object Spoof {

    private const val IOS_UA = "Spotify/9.0.58.558.g200011c iOS/17.7.2 (iPhone16,1)"
    private const val IOS_CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3"
    private const val IOS_HARDWARE = "iPhone16,1"
    private const val IOS_SYSTEM = "17.7.2"
    private const val SPOTIFY_SHA = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"

    private var proxyPort: Int = 0

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
        XposedBridge.log("SPOOF [INIT]: Inizializzazione modulo...")
        proxyPort = startLocalProxy()

        applyNativeHttpSpoof(classLoader)
        applySystemSpoof()
        applySignatureHook(classLoader)

        thread {
            Thread.sleep(1500) // Leggero delay per stabilità DexKit
            runCatching {
                val arch = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
                val libPath = "$moduleApkPath!/lib/$arch/libdexkit.so"
                System.load(libPath)
                XposedBridge.log("SPOOF [DEXKIT]: Libreria $arch caricata")

                DexKitBridge.create(apkPath).use { bridge ->
                    applyHooks(bridge, classLoader)
                }
            }.onFailure { XposedBridge.log("SPOOF [FATAL]: Errore DexKit: ${it.message}") }
        }
    }

    private fun applyHooks(bridge: DexKitBridge, classLoader: ClassLoader) {
        XposedBridge.log("SPOOF [CORE]: Inizio scansione e hook dinamici...")

        // 1, 2, 3. Configurazione Base
        val configHooks = mapOf(
            Fingerprints.setClientIdFingerprint to IOS_CLIENT_ID,
            Fingerprints.setUserAgentFingerprint to IOS_UA,
            Fingerprints.setAccessPointFingerprint to "http://127.0.0.1:$proxyPort"
        )

        configHooks.forEach { (fingerprint, value) ->
            runCatching {
                val methods = asMethodList(fingerprint(bridge))
                methods.forEach { mData ->
                    XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.args[0] = value
                            XposedBridge.log("SPOOF [CONFIG]: ${mData.name} -> $value")
                        }
                    })
                }
                if (methods.isNotEmpty()) XposedBridge.log("SPOOF [DEXKIT]: Hooked ${methods.size} metodi per config")
            }
        }

        // 4. Platform Getter
        runCatching {
            val pMethods = asMethodList(Fingerprints.loginClientPlatformFingerprint(bridge))
            pMethods.forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == "android") {
                            param.result = "ios"
                            XposedBridge.log("SPOOF [GETTER]: Forzato 'ios' su getter platform")
                        }
                    }
                })
            }
            XposedBridge.log("SPOOF [DEXKIT]: Hooked ${pMethods.size} getter platform")
        }

        // 5. Integrity Bypass
        runCatching {
            val integrityMethod = Fingerprints.runIntegrityVerificationFingerprint(bridge).getMethodInstance(classLoader)
            XposedBridge.hookMethod(integrityMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                    XposedBridge.log("SPOOF [BYPASS]: Metodo integrità bypassato")
                }
            })
        }

        // 6. Orbit Native Trigger
        runCatching {
            asMethodList(Fingerprints.orbitLibraryFingerprint(bridge)).forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("SPOOF [ORBIT]: Caricamento Orbit JNI rilevato in ${mData.name}")
                    }
                })
            }
        }

        // Hook per ClientInfo (Il passaporto del login)
        runCatching {
            val clientInfoMethods = asMethodList(Fingerprints.clientInfoFingerprint(bridge))
            clientInfoMethods.forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        when (param.method.name) {
                            "setPlatform" -> param.args[0] = "ios"
                            "setDeviceModel" -> param.args[0] = IOS_HARDWARE
                            "setOsVersion" -> param.args[0] = IOS_SYSTEM
                        }
                        XposedBridge.log("SPOOF [PROTO]: ClientInfo modificato -> ${param.method.name}")
                    }
                })
            }
        }

        // Hook per DeviceInformation (Dati sessione)
        runCatching {
            val deviceInfoMethods = asMethodList(Fingerprints.deviceInfoFingerprint(bridge))
            deviceInfoMethods.forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        when (param.method.name) {
                            "setOsVersion" -> param.args[0] = IOS_SYSTEM
                            "setDeviceModel" -> param.args[0] = IOS_HARDWARE
                        }
                        XposedBridge.log("SPOOF [PROTO]: DeviceInformation aggiornato")
                    }
                })
            }
        }

        // 7. Deep Spoof (Mappe e Protobuf dinamici)
        runCatching {
            val mapMethods = asMethodList(Fingerprints.loginMapFingerprint(bridge))
            XposedBridge.log("DEBUG: Trovati ${mapMethods.size} metodi Map potenziali") // QUESTO LOG È CRUCIALE
            if (mapMethods.isEmpty()) {
                XposedBridge.log("SPOOF ALERT: Fingerprint loginMap non ha trovato nulla!")
            }
            XposedBridge.log("SPOOF [DEEP]: Patching di ${mapMethods.size} potenziali punti di leak (Map/Proto)")
            mapMethods.forEach { mData ->
                runCatching {
                    XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val arg = param.args?.getOrNull(0) ?: return

                            // Gestione Mappe
                            if (arg is MutableMap<*, *>) {
                                @Suppress("UNCHECKED_CAST")
                                val map = arg as MutableMap<String, Any?>
                                if (map["platform"] == "android" || map["App-Platform"] == "android") {
                                    map["platform"] = "ios"
                                    map["App-Platform"] = "ios"
                                    XposedBridge.log("SPOOF [MAP]: Corretto valore platform in una Map")
                                }
                            }
                            // Gestione Protobuf (basato sui tuoi grep)
                            else if (arg.javaClass.name.startsWith("com.spotify")) {
                                arg.javaClass.declaredFields.forEach { field ->
                                    if (field.name == "platform_") {
                                        runCatching {
                                            field.isAccessible = true
                                            val oldVal = field.get(arg)
                                            if (field.type == String::class.java) {
                                                field.set(arg, "ios")
                                            } else {
                                                field.set(arg, 1) // ID iOS per tipi Int
                                            }
                                            XposedBridge.log("SPOOF [PROTO]: Patchato platform_ in ${arg.javaClass.simpleName} ($oldVal -> ios)")
                                        }
                                    }
                                }
                            }
                        }
                    })
                }
            }
        }

        // 8. Versioni e Hardware
        val vMap = mapOf(
            "getClientVersion" to "iphone-9.0.58.558.g200011c",
            "getSystemVersion" to IOS_SYSTEM,
            "getHardwareMachine" to IOS_HARDWARE
        )
        vMap.forEach { (type, value) ->
            val results = Fingerprints.findClientDataMethods(bridge, type)
            results.forEach { mData ->
                runCatching {
                    XposedBridge.hookMethod(mData.getMethodInstance(classLoader), de.robv.android.xposed.XC_MethodReplacement.returnConstant(value))
                }
            }
            XposedBridge.log("SPOOF [VERSION]: Patchati ${results.size} metodi per $type")
        }
        XposedBridge.log("SPOOF [CORE]: Tutti gli hook DexKit sono pronti.")
    }

    private fun applySystemSpoof() {
        runCatching {
            XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", IOS_HARDWARE)
            XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", IOS_HARDWARE)
            XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", IOS_SYSTEM)
            XposedBridge.log("SPOOF [SYSTEM]: Build properties emulate (Apple/iOS)")
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

                    if (url.contains("login") || url.contains("auth") || url.contains("tokens")) {
                        XposedBridge.log("SPOOF [NATIVE-HTTP]: Intercettata richiesta sensibile -> $url")
                        headersField?.let { field ->
                            val headers = field.get(req)
                            if (headers is MutableMap<*, *>) {
                                @Suppress("UNCHECKED_CAST")
                                val map = headers as MutableMap<String, String>
                                map["User-Agent"] = IOS_UA
                                map["X-Spotify-Client-Platform"] = "ios"
                                map["App-Platform"] = "ios"
                                XposedBridge.log("SPOOF [NATIVE-HTTP]: Header iniettati correttamente")
                            }
                        }
                    }
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
                        XposedBridge.log("SPOOF [SIGNATURE]: Firma Spotify falsificata per $pkg")
                    }
                }
            })
        }
    }

    private fun startLocalProxy(): Int {
        val server = ServerSocket(0)
        val port = server.localPort
        XposedBridge.log("SPOOF [PROXY]: Avviato sulla porta $port")
        thread(name = "SpoofProxy") {
            while (true) {
                runCatching { handleConnection(server.accept()) }
            }
        }
        return port
    }

    private fun handleConnection(socket: Socket) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val reader = input.bufferedReader()
                val firstLine = reader.readLine() ?: return@runCatching
                val parts = firstLine.split(" ")
                if (parts.size < 2) return@runCatching

                XposedBridge.log("SPOOF [PROXY]: Tunneling richiesta ${parts[0]} ${parts[1]}")

                val url = URL("https://login5.spotify.com" + parts[1])
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = parts[0]
                conn.doOutput = (parts[0] == "POST")
                conn.setRequestProperty("User-Agent", IOS_UA)
                conn.setRequestProperty("App-Platform", "ios")

                if (conn.doOutput) input.copyTo(conn.outputStream)
                val responseData = if (conn.responseCode < 400) conn.inputStream.readBytes() else conn.errorStream?.readBytes() ?: ByteArray(0)

                out.write("HTTP/1.1 ${conn.responseCode} OK\r\nContent-Length: ${responseData.size}\r\n\r\n".toByteArray())
                out.write(responseData)
                out.flush()
                XposedBridge.log("SPOOF [PROXY]: Risposta ${conn.responseCode} inoltrata")
            }.also { runCatching { socket.close() } }
        }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}