package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import kotlin.concurrent.thread

object Spoof {

    private const val SPOTIFY_SHA = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"

    // --- COSTANTI REVANCHE GOLD (Dati estratti dai diff e grep) ---
    private const val RE_CLIENT_VERSION = "iphone-9.0.58.558.g200011c"
    private const val RE_HARDWARE = "iPhone16,1"
    private const val RE_SYSTEM = "17.7.2"
    private const val RE_USER_AGENT = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"
    private const val RE_PLATFORM = "ios"

    fun apply(classLoader: ClassLoader, apkPath: String) {

        // 1. SPOOF DELLA FIRMA
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkg = param.args[0] as String
                    if (pkg.contains("spotify")) {
                        val info = param.result as? PackageInfo ?: return
                        info.signatures = arrayOf(Signature(hexToBytes(SPOTIFY_SHA)))
                        param.result = info
                    }
                }
            })
        }

        // 2. SPOOF DI SISTEMA (Proprietà Build)
        runCatching {
            XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", RE_HARDWARE)
            XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", RE_HARDWARE)
            XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", RE_SYSTEM)
            XposedBridge.log("SPOOF: Identità hardware impostata su $RE_HARDWARE")
        }

        // 3. HOOK DI RETE (ApplicationScopeConfiguration)
        // Dai diff classes11/12 si vede che ReVanced patcha direttamente qui
        runCatching {
            val configClass = XposedHelpers.findClass("com.spotify.connectivity.ApplicationScopeConfiguration", classLoader)
            XposedHelpers.findAndHookMethod(configClass, "getUserAgent", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any = RE_USER_AGENT
            })
        }

        // 4. LOGICHE DEXKIT
        runCatching { System.loadLibrary("dexkit") }
        thread {
            Thread.sleep(1500) // Un po' di respiro per il caricamento classi
            runCatching {
                DexKitBridge.create(apkPath).use { bridge ->

                    // --- A. SPOOF PIATTAFORMA ---
                    Fingerprints.findPlatformMethod(bridge).forEach { methodData ->
                        runCatching {
                            val method = methodData.getMethodInstance(classLoader)
                            XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(RE_PLATFORM))
                        }
                    }

                    // --- B. SPOOF VERSIONI E CLIENT ---
                    val targets = mapOf(
                        "getClientVersion" to RE_CLIENT_VERSION,
                        "getSystemVersion" to RE_SYSTEM,
                        "getHardwareMachine" to RE_HARDWARE
                    )

                    targets.forEach { (type, value) ->
                        Fingerprints.findClientDataMethods(bridge, type).forEach { methodData ->
                            runCatching {
                                val method = methodData.getMethodInstance(classLoader)
                                XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(value))
                            }
                        }
                    }

                    // --- C. BYPASS INTEGRITÀ (La patch Calendar.get) ---
                    // Fondamentale: deve restituire FALSE per evitare il blocco UI
                    Fingerprints.findIntegrityCheck(bridge).forEach { methodData ->
                        runCatching {
                            val method = methodData.getMethodInstance(classLoader)
                            XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(false))
                            XposedBridge.log("SPOOF: Integrity Check (Calendar) neutralizzato su ${method.name}")
                        }
                    }

                    // --- D. FORCE HTTP USER AGENT ---
                    // Questo intercetta il metodo "setDefaultHTTPUserAgent" e simili
                    Fingerprints.findUserAgentSetter(bridge).forEach { methodData ->
                        runCatching {
                            val method = methodData.getMethodInstance(classLoader)
                            // Se il metodo accetta una stringa (paramTypes("java.lang.String")),
                            // noi forziamo l'argomento in ingresso o il risultato.
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    // Sovrascriviamo l'argomento che l'app prova a impostare
                                    if (param.args.isNotEmpty() && param.args[0] is String) {
                                        param.args[0] = RE_USER_AGENT
                                    }
                                }
                            })
                            XposedBridge.log("SPOOF: HTTP User Agent Setter forzato in ${method.name}")
                        }
                    }
                }
            }.onFailure {
                XposedBridge.log("SPOOF ERROR: DexKit fallito -> ${it.message}")
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}