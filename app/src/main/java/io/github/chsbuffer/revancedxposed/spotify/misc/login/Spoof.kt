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

        // 1. FIRMA CHIRURGICA (Protegge l'SSL e inganna solo i check di Spotify)
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkg = param.args[0] as String
                    if (pkg.contains("spotify")) {
                        val stackTrace = Exception().stackTrace.joinToString { it.className }

                        // Solo se NON è Google o Conscrypt a chiedere la firma
                        if (!stackTrace.contains("com.google.android.gms") && !stackTrace.contains("org.conscrypt")) {
                            val info = param.result as? PackageInfo ?: return
                            info.signatures = arrayOf(Signature(hexToBytes(SPOTIFY_SHA)))
                            param.result = info
                        }
                    }
                }
            })
        }

        // 2. NEUTRALIZZAZIONE INTEGRITÀ (Ottimizzata sui tuoi risultati GREP)
        runCatching { System.loadLibrary("dexkit") }
        thread {
            Thread.sleep(3000)
            runCatching {
                DexKitBridge.create(apkPath).use { bridge ->
                    val integrityMethods = Fingerprints.findIntegrityCheck(bridge)
                    val protoMethods = Fingerprints.findIntegrityProto(bridge)

                    XposedBridge.log("SPOOF: Scansione completata. Check: ${integrityMethods.size}, Proto: ${protoMethods.size}")

                    (integrityMethods + protoMethods).forEach { methodData ->
                        runCatching {
                            val method = methodData.getMethodInstance(classLoader)
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val returnType = (param.method as java.lang.reflect.Method).returnType
                                    val methodName = param.method.name.lowercase()

                                    // Se il metodo restituisce una Stringa (il TOKEN trovato nel grep)
                                    if (returnType == String::class.java) {
                                        param.result = "" // Restituiamo vuoto: bypass per StandardIntegrity
                                        XposedBridge.log("SPOOF: Token neutralizzato in -> ${param.method.name}")
                                        return
                                    }

                                    // Gestione Booleani: forziamo successo se non è un metodo di "errore"
                                    if (returnType == Boolean::class.javaPrimitiveType) {
                                        param.result = !methodName.contains("error") && !methodName.contains("fail")
                                        return
                                    }

                                    // Per gli interi (codici di errore), 0 solitamente significa "Successo"
                                    if (returnType == Int::class.javaPrimitiveType) {
                                        param.result = 0
                                        return
                                    }

                                    // Per Void e altri oggetti, evitiamo il null forzato per non crashare
                                    if (returnType == Void.TYPE) {
                                        param.result = null
                                    }
                                }
                            })
                        }
                    }
                }
            }.onFailure {
                XposedBridge.log("SPOOF: Errore DexKit: ${it.message}")
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}