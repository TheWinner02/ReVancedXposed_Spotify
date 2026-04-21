package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

@SuppressLint("DiscouragedPrivateApi")
object Spoof {

    // Costanti iOS
    private const val IOS_UA = "Spotify/9.0.58.558.g200011c iOS/17.7.2 (iPhone16,1)"
    private const val IOS_CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3"
    private const val IOS_HARDWARE = "iPhone16,1"
    private const val IOS_SYSTEM = "17.7.2"
    private const val SPOTIFY_SHA = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"

    private var proxyPort: Int = 0

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun init(classLoader: ClassLoader, apkPath: String, moduleApkPath: String) {
        XposedBridge.log("SPOOF: Inizializzazione modulo...")

        proxyPort = startLocalProxy()
        XposedBridge.log("SPOOF: Proxy avviato sulla porta $proxyPort")

        applyNativeHttpSpoof(classLoader)
        applySystemSpoof()
        applySignatureHook(classLoader)

        // Inizializzazione DexKit (lenta, in background)
        thread {
            // Un piccolo delay aiuta ad evitare conflitti durante il caricamento pesante di Spotify
            Thread.sleep(1000)
            runCatching {
                // FIX DEFINITIVO PER IL CARICAMENTO DELLA LIBRERIA NATIVA
                val arch = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
                val libPath = "$moduleApkPath!/lib/$arch/libdexkit.so"

                try {
                    System.load(libPath)
                    XposedBridge.log("SPOOF: DexKit caricato correttamente (System.load)")
                } catch (e: Throwable) {
                    XposedBridge.log("SPOOF DEBUG: System.load fallito (${e.message}), provo fallback...")
                    try {
                        System.loadLibrary("dexkit")
                        XposedBridge.log("SPOOF: DexKit caricato (fallback loadLibrary)")
                    } catch (_: Throwable) {
                        XposedBridge.log("SPOOF WARNING: Fallback fallito, DexKit proverà l'estrazione interna")
                    }
                }

                DexKitBridge.create(apkPath).use { bridge ->
                    XposedBridge.log("SPOOF: Bridge DexKit creato, applico hooks...")
                    applyHooks(bridge, classLoader)
                    XposedBridge.log("SPOOF: Tutti gli hook DexKit sono attivi.")
                }
            }.onFailure {
                XposedBridge.log("SPOOF FATAL: Errore durante l'inizializzazione DexKit: ${it.message}")
                it.printStackTrace()
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

    private fun applyHooks(bridge: DexKitBridge, classLoader: ClassLoader) {

        // --- 1. RICERCA DINAMICA DELLA CLASSE DI CONFIGURAZIONE ---
        val configClass: Class<*>? = runCatching {
            val methodDataList = bridge.findMethod {
                matcher {
                    name = "setDefaultHTTPUserAgent"
                    parameters("java.lang.String")
                }
            }
            if (methodDataList.isNotEmpty()) {
                methodDataList[0].getMethodInstance(classLoader).declaringClass
            } else null
        }.getOrNull()

        if (configClass != null) {
            XposedBridge.log("SPOOF: Trovata classe Config: ${configClass.name}")

            // A. Access Point (Cerchiamo il metodo nella classe trovata)
            val apMethod = configClass.declaredMethods.find { m ->
                m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == String::class.java &&
                        (m.name == "setAccessPoint" || m.name.contains("AccessPoint", true))
            }

            apMethod?.let { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = "http://127.0.0.1:$proxyPort"
                        XposedBridge.log("SPOOF: AccessPoint patchato su ${method.name}")
                    }
                })
            } ?: XposedBridge.log("SPOOF ERROR: Metodo AccessPoint non trovato")

            // B & C. Client ID & User Agent
            runCatching {
                XposedHelpers.findAndHookMethod(configClass, "setClientId", String::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = IOS_CLIENT_ID }
                })
                XposedHelpers.findAndHookMethod(configClass, "setDefaultHTTPUserAgent", String::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = IOS_UA }
                })
            }
        }

        // --- LOGICA DIFFUSA (Versioni e Hardware) ---
        val targets = mapOf(
            "getClientVersion" to "iphone-9.0.58.558.g200011c",
            "getSystemVersion" to "17.7.2",
            "getHardwareMachine" to IOS_HARDWARE
        )

        targets.forEach { (type: String, value: String) ->
            // Specifichiamo il tipo esplicito della lista per evitare Unresolved reference 'isEmpty'
            val methods: List<org.luckypray.dexkit.result.MethodData> = Fingerprints.findClientDataMethods(bridge, type)

            if (methods.isNotEmpty()) {
                XposedBridge.log("SPOOF DEBUG: Patcho ${methods.size} metodi per $type")
                methods.forEach { mData: org.luckypray.dexkit.result.MethodData ->
                    runCatching {
                        val method = mData.getMethodInstance(classLoader)
                        XposedBridge.hookMethod(method, de.robv.android.xposed.XC_MethodReplacement.returnConstant(value))
                    }
                }
            }
        }

        // --- D. INTEGRITY BYPASS ---
        runCatching {
            val integrityMethod = Fingerprints.runIntegrityVerificationFingerprint(bridge).getMethodInstance(classLoader)
            XposedBridge.hookMethod(integrityMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                    XposedBridge.log("SPOOF: Integrity bypass eseguito")
                }
            })
        }

        // --- E. PLATFORM SPOOF ---
        runCatching {
            // Specifichiamo il tipo per l'invocazione della funzione fingerprint
            val platformFunc: FindMethodFunc = Fingerprints.loginClientPlatformFingerprint
            val platformMethod = platformFunc(bridge).getMethodInstance(classLoader)

            XposedBridge.hookMethod(platformMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result == "android") {
                        param.result = "ios"
                        XposedBridge.log("SPOOF DEBUG: Platform convertita android -> ios")
                    }
                }
            })
        }.onFailure { XposedBridge.log("SPOOF ERROR: Platform hook fallito") }
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