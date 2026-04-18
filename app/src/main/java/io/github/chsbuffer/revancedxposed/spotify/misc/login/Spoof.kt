package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.pm.PackageInfo
import android.content.pm.Signature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge

object Spoof {

    private const val SPOTIFY_SHA = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"

    fun apply(classLoader: ClassLoader, apkPath: String) {

        // 1. SPOOF DELLA FIRMA (Livello OS)
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
            XposedBridge.log("SPOOF: Firma bypassata.")
        }

        // 2. SPOOF USER-AGENT (iOS) - Metodo Diretto
        // Molti controlli 14gg passano dall'header HTTP. Hookiamo la classe di configurazione.
        runCatching {
            val configClass = XposedHelpers.findClass("com.spotify.connectivity.ApplicationScopeConfiguration", classLoader)
            XposedHelpers.findAndHookMethod(configClass, "setDefaultHTTPUserAgent", String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = "Spotify/8.9.10 iOS/17.4.1 (iPhone14,5)"
                    XposedBridge.log("SPOOF: User-Agent impostato su iOS")
                }
            })
        }

        // 3. LOGICHE DEXKIT (Integrità e Metodi Interni)
        runCatching {
            System.loadLibrary("dexkit")

            DexKitBridge.create(apkPath).use { bridge ->
                XposedBridge.log("SPOOF: Inizio scansione DexKit...")

                // --- BYPASS INTEGRITÀ ---
                // Usa la nuova ricerca "invokeMethods" di Fingerprints.kt
                val integrityMethods = Fingerprints.findIntegrityCheck(bridge)
                integrityMethods.forEach { methodData ->
                    val method = methodData.getMethodInstance(classLoader)
                    XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(false))
                    XposedBridge.log("SPOOF: Integrità disabilitata su -> ${method.name}")
                }

                // --- SPOOF METODI CLIENT ---
                val targetMethods = listOf("getClientVersion", "getSystemVersion", "getHardwareMachine")
                targetMethods.forEach { methodName ->
                    val methods = Fingerprints.findClientDataMethods(bridge, methodName)
                    methods.forEach { methodData ->
                        val method = methodData.getMethodInstance(classLoader)

                        val fakeValue = when(methodName) {
                            "getClientVersion" -> "8.9.10"
                            "getSystemVersion" -> "iOS 17.4.1"
                            "getHardwareMachine" -> "iPhone14,5"
                            else -> ""
                        }

                        XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(fakeValue))
                        XposedBridge.log("SPOOF: Metodo ${method.name} patchato -> $fakeValue")
                    }
                }
            }
        }.onFailure {
            XposedBridge.log("SPOOF ERROR: DexKit fallito -> ${it.message}")
        }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}