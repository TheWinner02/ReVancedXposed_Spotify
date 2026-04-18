package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.pm.PackageInfo
import android.content.pm.Signature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge

object Spoof {

    // Firma originale di Spotify per eludere i check di base
    private const val SPOTIFY_SHA = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"

    fun apply(classLoader: ClassLoader, apkPath: String) {

        // 1. SPOOF DELLA FIRMA (OS Level)
        // Equivalente di SpoofSignaturePatchKt e SpoofPackageInfoPatchKt.
        // Poiché siamo su Xposed, hookare l'API di sistema è infinitamente più stabile
        // che cercare di patchare le 10+ chiamate interne di Spotify.
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
            XposedBridge.log("DEXKIT-SPOOF: Firma Spotify spoofata a livello OS.")
        }

        // 2. PATCH PROFONDE CON DEXKIT (Integrità & Spoof iOS)
        runCatching {
            System.loadLibrary("dexkit") // Carichiamo il motore C++

            DexKitBridge.create(apkPath).use { bridge ->
                XposedBridge.log("DEXKIT-SPOOF: Avvio scansione bytecode...")

                // --- BYPASS INTEGRITÀ ---
                // Se questo fallisce, Spotify dà schermo nero o popup. Lo disabilitiamo (false).
                val integrityMethods = Fingerprints.findIntegrityCheck(bridge)
                integrityMethods.forEach { methodData ->
                    val method = methodData.getMethodInstance(classLoader)
                    XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(false))
                    XposedBridge.log("DEXKIT-SPOOF: Integrità bypassata su ${method.name}")
                }

                // --- SPOOF CLIENT (FORZATURA iOS) ---
                // Sostituiamo i metodi interni per far credere ai server di essere un iPhone
                val targetMethods = listOf("getClientVersion", "getSystemVersion", "getHardwareMachine")

                targetMethods.forEach { methodName ->
                    val methods = Fingerprints.findClientDataMethods(bridge, methodName)
                    methods.forEach { methodData ->
                        val method = methodData.getMethodInstance(classLoader)

                        val fakeValue = when(methodName) {
                            "getClientVersion" -> "8.9.10" // Una versione supportata
                            "getSystemVersion" -> "iOS 17.4.1" // SPOOF SISTEMA OPERATIVO
                            "getHardwareMachine" -> "iPhone14,5" // SPOOF HARDWARE (iPhone 13)
                            else -> ""
                        }

                        XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(fakeValue))
                        XposedBridge.log("DEXKIT-SPOOF: ${method.name} -> $fakeValue")
                    }
                }
            }
        }.onFailure {
            XposedBridge.log("DEXKIT-SPOOF ERRORE: Scansione fallita -> ${it.message}")
            it.printStackTrace()
        }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}