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

    // Costanti estratte dai tuoi log ReVanced (Sincronizzazione perfetta)
    private const val RE_CLIENT_VERSION = "iphone-9.0.58.558.g200011c"
    private const val RE_HARDWARE = "iPhone13,1"
    private const val RE_SYSTEM = "15"
    private const val RE_USER_AGENT = "Spotify/9.0.58 iOS/15(iPhone13,1)"
    private const val RE_PLATFORM = "ios" // Trovato in vx6.smali

    fun apply(classLoader: ClassLoader, apkPath: String) {

        // 1. SPOOF DELLA FIRMA (Previene il ban rilevando la firma originale)
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
            XposedBridge.log("SPOOF: Firma bypassata con successo.")
        }

        // 2. SPOOF DI SISTEMA (BUILD)
        // Inganna le librerie native che leggono direttamente da android.os.Build
        runCatching {
            XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", RE_HARDWARE)
            XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", RE_HARDWARE)
            XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", RE_SYSTEM)
            XposedBridge.log("SPOOF: Proprietà Build impostate su $RE_HARDWARE ($RE_SYSTEM)")
        }

        // 3. CONFIGURAZIONE DI RETE DIRETTA (Trovata tramite Grep)
        // Questa classe gestisce la connettività e non è offuscata in questa versione
        runCatching {
            val configClass = XposedHelpers.findClass("com.spotify.connectivity.ApplicationScopeConfiguration", classLoader)
            XposedHelpers.findAndHookMethod(configClass, "getUserAgent", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any = RE_USER_AGENT
            })
            XposedBridge.log("SPOOF: Hook diretto su ApplicationScopeConfiguration (User-Agent).")
        }

        // 4. LOGICHE DEXKIT (Piattaforma, Versioni e Integrità)
        runCatching { System.loadLibrary("dexkit") }
        thread {
            Thread.sleep(1000)
            XposedBridge.log("SPOOF: Thread avviato")
            runCatching {
                DexKitBridge.create(apkPath).use { bridge ->

                    // --- SPOOF PIATTAFORMA (Fondamentale per il login iOS) ---
                    // Cerca il metodo che restituisce "android" e forzalo a "ios"
                    Fingerprints.findPlatformMethod(bridge).forEach { methodData ->
                        runCatching {
                            val method = methodData.getMethodInstance(classLoader)
                            XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(RE_PLATFORM))
                            XposedBridge.log("SPOOF: Piattaforma forzata a $RE_PLATFORM in ${method.name}")
                        }
                    }

                    // --- SPOOF METODI CLIENT (Versioni e Hardware) ---
                    val targets = mapOf(
                        "getClientVersion" to RE_CLIENT_VERSION,
                        "getSystemVersion" to RE_SYSTEM,
                        "getHardwareMachine" to RE_HARDWARE
                    )

                    targets.forEach { (type, value) ->
                        val methods = Fingerprints.findClientDataMethods(bridge, type)
                        if (methods.isEmpty()) {
                            XposedBridge.log("SPOOF: Attenzione, nessun metodo trovato per $type")
                        }
                        methods.forEach { methodData ->
                            runCatching {
                                val method = methodData.getMethodInstance(classLoader)
                                XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(value))
                                XposedBridge.log("SPOOF: $type patchato -> $value in ${method.name}")
                            }
                        }
                    }

                    // --- BYPASS INTEGRITÀ ---
                    Fingerprints.findIntegrityCheck(bridge).forEach { methodData ->
                        runCatching {
                            val method = methodData.getMethodInstance(classLoader)
                            // Essendo void, usiamo DO_NOTHING invece di returnConstant(null)
                            XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
                            XposedBridge.log("SPOOF: Integrity check disabilitato in ${method.name}")
                        }
                    }
                }
            }.onFailure {
                XposedBridge.log("SPOOF ERROR: DexKit fallito -> ${it.message}")
                XposedBridge.log("SPOOF ERROR: DexKit stacktrace -> ${it.stackTraceToString()}")
                XposedBridge.log("SPOOF ERROR: DexKit cause -> ${it.cause}")
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}