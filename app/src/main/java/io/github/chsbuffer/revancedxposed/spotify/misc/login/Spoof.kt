package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.Signature
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.chsbuffer.revancedxposed.* // Importa hookMethod, before, etc.
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

    private const val IOS_UA = "Spotify/9.0.58.558.g200011c iOS/17.7.2 (iPhone16,1)"
    private const val IOS_CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3"
    private const val SPOTIFY_SHA = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"

    private var proxyPort: Int = 0

    fun init(classLoader: ClassLoader, apkPath: String, moduleApkPath: String) {
        proxyPort = startLocalProxy()

        applyNativeHttpSpoof(classLoader)

        thread {
            Thread.sleep(1000)
            runCatching {
                val arch = if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
                val libPath = "$moduleApkPath!/lib/$arch/libdexkit.so"

                System.loadLibrary(libPath)
                XposedBridge.log("SPOOF: DexKit caricato da $libPath")

                DexKitBridge.create(apkPath).use { bridge ->
                    // PASSIAMO IL CLOASSLOADER PER LA CONVERSIONE
                    applyHooks(bridge, classLoader)
                }
            }.onFailure {
                XposedBridge.log("SPOOF ERROR: DexKit fallito -> ${it.message}")
                it.printStackTrace()
            }
        }
        applySignatureHook()
    }

    private fun applyNativeHttpSpoof(cl: ClassLoader) {
        runCatching {
            val httpConnectionImpl = cl.loadClass("com.spotify.core.http.NativeHttpConnection")
            val httpRequest = cl.loadClass("com.spotify.core.http.HttpRequest")

            // Trova i campi necessari tramite riflessione
            val urlField = httpRequest.getDeclaredField("url").apply { isAccessible = true }
            val headersField = httpRequest.declaredFields.find {
                it.type == Map::class.java || it.type.name.contains("Headers", true)
            }?.apply { isAccessible = true }

            XposedBridge.hookAllMethods(httpConnectionImpl, "send", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args[0] ?: return
                    val url = urlField.get(req) as? String ?: return

                    // Filtro per gli endpoint di autenticazione e configurazione
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
                                // XposedBridge.log("SPOOF G: Headers iOS applicati a $url")
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

        // LA LOGICA È: Fingerprint -> MethodData -> Member (tramite getMethodInstance) -> hookMethod

        // A. Access Point
        Fingerprints.setAccessPointFingerprint(bridge).getMethodInstance(classLoader).hookMethod {
            before { param: MethodHookParam ->
                param.args[0] = "http://127.0.0.1:$proxyPort"
                XposedBridge.log("SPOOF: AccessPoint patchato")
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
                if (param.result == "android") param.result = "ios"
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun applySignatureHook() {
        runCatching {
            val method = Class.forName("android.app.ApplicationPackageManager")
                .getDeclaredMethod("getPackageInfo", String::class.java, Int::class.javaPrimitiveType)

            XposedBridge.hookMethod(method, object : XC_MethodHook() {
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

                val url = URL("http://googleusercontent.com/spotify.com/9$path")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.doOutput = (method == "POST")

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
                socket.close()
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}