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

    // Helper per gestire i risultati misti (Singolo o Lista) di DexKit
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
        XposedBridge.log("SPOOF: Inizializzazione...")
        proxyPort = startLocalProxy()

        applyNativeHttpSpoof(classLoader)
        applySystemSpoof()
        applySignatureHook(classLoader)

        thread {
            Thread.sleep(1000)
            runCatching {
                val arch = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
                val libPath = "$moduleApkPath!/lib/$arch/libdexkit.so"
                System.load(libPath)

                DexKitBridge.create(apkPath).use { bridge ->
                    applyHooks(bridge, classLoader)
                }
            }.onFailure { XposedBridge.log("SPOOF FATAL: DexKit Init Error: ${it.message}") }
        }
    }

    private fun applyHooks(bridge: DexKitBridge, classLoader: ClassLoader) {
        XposedBridge.log("SPOOF: Applicazione hook dinamici...")

        // 1. Client ID
        runCatching {
            asMethodList(Fingerprints.setClientIdFingerprint(bridge)).forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = IOS_CLIENT_ID }
                })
            }
        }

        // 2. User Agent
        runCatching {
            asMethodList(Fingerprints.setUserAgentFingerprint(bridge)).forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = IOS_UA }
                })
            }
        }

        // 3. Access Point (Proxy)
        runCatching {
            asMethodList(Fingerprints.setAccessPointFingerprint(bridge)).forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = "http://127.0.0.1:$proxyPort"
                    }
                })
            }
        }

        // 4. Platform Spoof (Getter)
        runCatching {
            val methods = asMethodList(Fingerprints.loginClientPlatformFingerprint(bridge))
            methods.forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == "android") param.result = "ios"
                    }
                })
            }
            XposedBridge.log("SPOOF: Patchati ${methods.size} getter platform")
        }

        // 5. Integrity Bypass
        runCatching {
            val m = Fingerprints.runIntegrityVerificationFingerprint(bridge).getMethodInstance(classLoader)
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
            })
            XposedBridge.log("SPOOF: Integrity Bypass attivo")
        }

        // 6. Orbit Library Load Trigger
        runCatching {
            asMethodList(Fingerprints.orbitLibraryFingerprint(bridge)).forEach { mData ->
                XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) { XposedBridge.log("SPOOF: Orbit Native Logic caricata") }
                })
            }
        }

        // 7. Deep Spoof (Maps/Protobuf)
        runCatching {
            val mapMethods = asMethodList(Fingerprints.loginMapFingerprint(bridge))
            XposedBridge.log("SPOOF: Deep Spoof su ${mapMethods.size} metodi")
            mapMethods.forEach { mData ->
                runCatching {
                    XposedBridge.hookMethod(mData.getMethodInstance(classLoader), object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val arg = param.args?.getOrNull(0) ?: return
                            if (arg is MutableMap<*, *>) {
                                @Suppress("UNCHECKED_CAST")
                                val map = arg as MutableMap<String, Any?>
                                if (map["platform"] == "android" || map["App-Platform"] == "android") {
                                    map["platform"] = "ios"
                                    map["App-Platform"] = "ios"
                                }
                            } else if (arg.javaClass.name.startsWith("com.spotify")) {
                                runCatching { XposedHelpers.setObjectField(arg, "platform_", "ios") }
                            }
                        }
                    })
                }
            }
        }

        // 8. Versioni e Hardware
        listOf("getClientVersion", "getSystemVersion", "getHardwareMachine").forEach { type ->
            val value = when(type) {
                "getClientVersion" -> "iphone-9.0.58.558.g200011c"
                "getSystemVersion" -> IOS_SYSTEM
                else -> IOS_HARDWARE
            }
            Fingerprints.findClientDataMethods(bridge, type).forEach { mData ->
                runCatching {
                    XposedBridge.hookMethod(mData.getMethodInstance(classLoader), de.robv.android.xposed.XC_MethodReplacement.returnConstant(value))
                }
            }
        }
    }

    private fun applySystemSpoof() {
        runCatching {
            XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", IOS_HARDWARE)
            XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", IOS_HARDWARE)
            XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", IOS_SYSTEM)
            XposedBridge.log("SPOOF: Build prop modificate (iPhone Mode)")
        }.onFailure {
            XposedBridge.log("SPOOF ERROR: Impossibile modificare Build props: ${it.message}")
        }
    }

    private fun applyNativeHttpSpoof(cl: ClassLoader) {
        runCatching {
            val httpConnectionImpl = cl.loadClass("com.spotify.core.http.NativeHttpConnection")
            val httpRequest = cl.loadClass("com.spotify.core.http.HttpRequest")

            val urlField = httpRequest.getDeclaredField("url").apply { isAccessible = true }
            val headersField = httpRequest.declaredFields.find {
                it.type == Map::class.java || it.type.name.contains("Headers", true)
            }?.apply { isAccessible = true }

            XposedBridge.hookAllMethods(httpConnectionImpl, "send", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args[0] ?: return
                    val url = urlField.get(req) as? String ?: return

                    if (url.contains("login") || url.contains("auth") || url.contains("tokens") ||
                        url.contains("identify") || url.contains("bootstrap")) {

                        XposedBridge.log("SPOOF G: Intercettata chiamata nativa sensibile: $url")
                        headersField?.let { field ->
                            val headers = field.get(req)
                            if (headers is MutableMap<*, *>) {
                                @Suppress("UNCHECKED_CAST")
                                val map = headers as MutableMap<String, String>
                                map["User-Agent"] = IOS_UA
                                map["X-Spotify-Client-Platform"] = "ios"
                                map["App-Platform"] = "ios"
                                // XposedBridge.log("SPOOF G: Headers iOS iniettati nel traffico nativo")
                            }
                        }
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("SPOOF ERROR: NativeHttp hook fallito: ${it.message}")
        }
    }

    private fun applySignatureHook(classLoader: ClassLoader) {
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
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
            XposedBridge.log("SPOOF: Signature hook applicato")
        }.onFailure { XposedBridge.log("SPOOF ERROR: Signature hook fallito: ${it.message}") }
    }

    // --- PROXY LOCALE ---
    private fun startLocalProxy(): Int {
        val server = ServerSocket(0)
        val port = server.localPort
        thread(name = "SpoofProxyMain") {
            while (true) {
                runCatching {
                    val socket = server.accept()
                    handleConnection(socket)
                }
            }
        }
        return port
    }

    private fun handleConnection(socket: Socket) {
        val connId = System.currentTimeMillis().toString().takeLast(4)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val reader = input.bufferedReader()
                val firstLine = reader.readLine() ?: return@runCatching

                val parts = firstLine.split(" ")
                if (parts.size < 2) return@runCatching
                val method = parts[0]
                val path = parts[1]

                XposedBridge.log("SPOOF PROXY [$connId]: $method -> $path")

                val url = URL("com.spotify.music/cache/n3ptun3/cxit.uygwazdnihh.n3p/4201124421.n3ptun3!/lib/arm64-v8a/libdexkit.so$path")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.doOutput = (method == "POST")

                // Header forzati in uscita
                conn.setRequestProperty("User-Agent", IOS_UA)
                conn.setRequestProperty("X-Client-Id", IOS_CLIENT_ID)
                conn.setRequestProperty("App-Platform", "ios")

                if (conn.doOutput) input.copyTo(conn.outputStream)

                val responseCode = conn.responseCode
                XposedBridge.log("SPOOF PROXY [$connId]: Risposta Spotify -> $responseCode")

                val responseData = if (responseCode < 400) conn.inputStream.readBytes() else conn.errorStream?.readBytes() ?: ByteArray(0)

                out.write("HTTP/1.1 $responseCode OK\r\n".toByteArray())
                out.write("Content-Length: ${responseData.size}\r\n\r\n".toByteArray())
                out.write(responseData)
                out.flush()
            }.onFailure {
                XposedBridge.log("SPOOF PROXY ERROR [$connId]: ${it.message}")
            }.also {
                // ASSICURA LA CHIUSURA DEL SOCKET
                runCatching { socket.close() }
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}