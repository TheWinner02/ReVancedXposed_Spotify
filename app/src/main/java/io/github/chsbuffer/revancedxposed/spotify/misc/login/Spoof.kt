package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.pm.PackageInfo
import android.content.pm.Signature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import kotlin.concurrent.thread

object Spoof {

    private const val SPOTIFY_SHA = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"

    fun apply(classLoader: ClassLoader, apkPath: String) {

        // 1. FIRMA CHIRURGICA (Salva l'SSL e inganna solo Spotify)
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkg = param.args[0] as String
                    if (pkg.contains("spotify")) {
                        // Controlla chi sta chiedendo la firma
                        val stackTrace = Exception().stackTrace.joinToString { it.className }

                        // Se è Google (SSL/Rete) lasciamo l'originale. Se è Spotify (Sicurezza), diamo la falsa.
                        if (!stackTrace.contains("com.google.android.gms") && !stackTrace.contains("org.conscrypt")) {
                            val info = param.result as? PackageInfo ?: return
                            info.signatures = arrayOf(Signature(hexToBytes(SPOTIFY_SHA)))
                            param.result = info
                            // XposedBridge.log("SPOOF: Firma protetta applicata.") // Decommenta se vuoi loggare
                        }
                    }
                }
            })
        }

        // 2. NEUTRALIZZAZIONE INTEGRITÀ (DexKit)
        runCatching { System.loadLibrary("dexkit") }
        thread {
            Thread.sleep(1500) // Diamo tempo all'app di caricare
            runCatching {
                DexKitBridge.create(apkPath).use { bridge ->
                    val integrityMethods = Fingerprints.findIntegrityCheck(bridge)
                    val protoMethods = Fingerprints.findIntegrityProto(bridge)

                    XposedBridge.log("DEBUG: Trovati ${integrityMethods.size} check e ${protoMethods.size} proto.")

                    (integrityMethods + protoMethods).forEach { methodData ->
                        runCatching {
                            val method = methodData.getMethodInstance(classLoader)
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val returnType = (param.method as java.lang.reflect.Method).returnType

                                    // Passiamo stringa vuota ai Token Setter
                                    if (param.args.isNotEmpty() && param.args[0] == null && param.method.name.contains("Token")) {
                                        param.args[0] = ""
                                    }

                                    // Bypassiamo i controlli
                                    when {
                                        returnType == Void.TYPE -> param.result = null
                                        returnType == Boolean::class.javaPrimitiveType -> param.result = true
                                        returnType == Int::class.javaPrimitiveType -> param.result = 0
                                        else -> param.result = null
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}