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

        // 1. FIRMA CHIRURGICA (Inganna Spotify ma non rompe l'SSL/Google)
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkg = param.args[0] as String
                    if (pkg.contains("spotify")) {
                        val stackTrace = Exception().stackTrace.joinToString { it.className }
                        if (!stackTrace.contains("com.google.android.gms") && !stackTrace.contains("org.conscrypt")) {
                            val info = param.result as? PackageInfo ?: return
                            info.signatures = arrayOf(Signature(hexToBytes(SPOTIFY_SHA)))
                            param.result = info
                        }
                    }
                }
            })
        }

        // 2. NEUTRALIZZAZIONE INTEGRITÀ
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

                            // LOG DI DEBUG: Ci conferma che l'hook è attivo su questo metodo
                            XposedBridge.log("SPOOF: Applicazione hook su -> ${method.declaringClass.name}.${method.name}")

                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val returnType = (param.method as java.lang.reflect.Method).returnType
                                    val methodName = param.method.name.lowercase()

                                    // Se il metodo restituisce una Stringa (è il TOKEN trovato nel grep)
                                    if (returnType == String::class.java) {
                                        param.result = "" // Bypass: inviamo una stringa vuota invece del token criptato
                                        XposedBridge.log("SPOOF: Chiamata intercettata! Token rimpiazzato in -> ${param.method.name}")
                                        return
                                    }

                                    // Gestione Booleani
                                    if (returnType == Boolean::class.javaPrimitiveType) {
                                        param.result = !methodName.contains("error") && !methodName.contains("fail")
                                        XposedBridge.log("SPOOF: Booleano forzato in -> ${param.method.name} a ${param.result}")
                                        return
                                    }

                                    // Per gli interi (codici di errore), 0 = Successo
                                    if (returnType == Int::class.javaPrimitiveType) {
                                        param.result = 0
                                        return
                                    }

                                    if (returnType == Void.TYPE) {
                                        param.result = null
                                    }
                                }
                            })
                        }
                    }
                }
            }.onFailure {
                XposedBridge.log("SPOOF: Errore durante l'analisi DexKit: ${it.message}")
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}