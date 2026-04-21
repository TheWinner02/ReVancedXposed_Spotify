package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
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
        // 1. Avvia il Proxy Locale (immediato)
        proxyPort = startLocalProxy()

        // 2. Spoof delle chiamate HTTP Native (immediato)
        applyNativeHttpSpoof(classLoader)

        // 3. Spoof Hardware e Firma (immediato)
        applySystemSpoof()
        applySignatureHook(classLoader)

        // 4. Inizializzazione DexKit (lenta, in background)
        thread {
            // Un piccolo delay aiuta ad evitare conflitti durante il caricamento pesante di Spotify
            Thread.sleep(500)

            runCatching {
                // FIX DEFINITIVO PER IL CARICAMENTO DELLA LIBRERIA NATIVA
                val arch = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
                val libPath = "$moduleApkPath!/lib/$arch/libdexkit.so"

                try {
                    // System.load accetta il percorso completo
                    System.load(libPath)
                    XposedBridge.log("SPOOF: DexKit caricato via Path -> $libPath")
                } catch (_: Throwable) {
                    XposedBridge.log("SPOOF: System.load fallito, provo fallback System.loadLibrary...")
                    try {
                        System.loadLibrary("dexkit")
                    } catch (_: Throwable) {
                        XposedBridge.log("SPOOF: Affido il caricamento interno a DexKitBridge")
                    }
                }

                DexKitBridge.create(apkPath).use { bridge ->
                    applyHooks(bridge, classLoader)
                    XposedBridge.log("SPOOF: Tutti gli hook DexKit applicati con successo.")
                }
            }.onFailure {
                XposedBridge.log("SPOOF ERROR: DexKit fallito criticamente -> ${it.message}")
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
        }.onFailure {
            XposedBridge.log("SPOOF ERROR: Fallito spoof Build -> ${it.message}")
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
                        url.contains("identify") || url.contains("bootstrap") || url.contains("config") ||
                        url.contains("user-attributes")) {

                        headersField?.let { field ->
                            val headers = field.get(req)
                            if (headers is MutableMap<*, *>) {
                                @Suppress("UNCHECKED_CAST")
                                val map = headers as MutableMap<String, String>
                                map["User-Agent"] = IOS_UA
                                map["X-Spotify-Client-Platform"] = "ios"
                                map["App-Platform"] = "ios"
                            }
                        }
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("SPOOF ERROR: Fallito hook NativeHttp (G) -> ${it.message}")
        }
    }

    private fun applyHooks(bridge: DexKitBridge, classLoader: ClassLoader) {

        // A. Access Point -> Proxy Locale
        Fingerprints.setAccessPointFingerprint(bridge).getMethodInstance(classLoader).hookMethod {
            before { param: MethodHookParam ->
                param.args[0] = "http://127.0.0.1:$proxyPort"
                XposedBridge.log("SPOOF: AccessPoint -> 127.0.0.1:$proxyPort")
            }
        }

        // B. Client ID
        Fingerprints.setClientIdFingerprint(bridge).getMethodInstance(classLoader).hookMethod {
            before { param: MethodHookParam -> param.args[0] = IOS_CLIENT_ID }
        }

        // C. User Agent
        Fingerprints.setUserAgentFingerprint(bridge).getMethodInstance(classLoader).hookMethod {
            before { param: MethodHookParam -> param.args[0] = IOS_UA }
        }

        // --- NUOVA LOGICA: Spoofing diffuso di Versioni e Hardware ---
        val targets = mapOf(
            "getClientVersion" to "iphone-9.0.58.558.g200011c",
            "getSystemVersion" to "17.7.2",
            "getHardwareMachine" to "iPhone16,1" // IOS_HARDWARE
        )

        targets.forEach { (type, value) ->
            val methods = Fingerprints.findClientDataMethods(bridge, type)
            methods.forEach { methodData ->
                runCatching {
                    val method = methodData.getMethodInstance(classLoader)
                    // Usiamo returnConstant per forzare il valore di ritorno dei getter
                    XposedBridge.hookMethod(method, de.robv.android.xposed.XC_MethodReplacement.returnConstant(value))
                }
            }
        }

        // D. Integrity Bypass
        Fingerprints.runIntegrityVerificationFingerprint(bridge).getMethodInstance(classLoader).hookMethod {
            replace {
                XposedBridge.log("SPOOF: Integrity bypass eseguito")
                null
            }
        }

        // E. Platform Spoof
        Fingerprints.loginClientPlatformFingerprint(bridge).getMethodInstance(classLoader).hookMethod {
            after { param: MethodHookParam ->
                if (param.result == "android") {
                    param.result = "ios"
                }
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun applySignatureHook(classLoader: ClassLoader) {
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
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
        }.onFailure {
            XposedBridge.log("SPOOF ERROR: Hook firma fallito -> ${it.message}")
        }
    }

    // --- PROXY LOCALE ---
    private fun startLocalProxy(): Int {
        val server = ServerSocket(0)
        val port = server.localPort
        thread {
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
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val reader = input.bufferedReader()
                val firstLine = reader.readLine() ?: return@runCatching
                val parts = firstLine.split(" ")
                val method = parts[0]
                val path = parts[1]

                // Redirezione proxy
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
                val responseData = if (responseCode < 400) conn.inputStream.readBytes() else conn.errorStream?.readBytes() ?: ByteArray(0)

                out.write("HTTP/1.1 $responseCode OK\r\n".toByteArray())
                out.write("Content-Length: ${responseData.size}\r\n\r\n".toByteArray())
                out.write(responseData)
                out.flush()
            }.onFailure {
                // Log opzionale per debug proxy: XposedBridge.log("SPOOF PROXY: Timeout/Errore")
            }.also {
                // ASSICURA LA CHIUSURA DEL SOCKET
                runCatching { socket.close() }
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}