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

        // 1. FIRMA UNIVERSALE E CHIRURGICA (Unificata per oPatch)
        runCatching {
            val pmClass = Class.forName("android.app.ApplicationPackageManager")
            val getPackageInfoMethod = pmClass.getDeclaredMethod("getPackageInfo", String::class.java, Int::class.javaPrimitiveType)

            XposedBridge.hookMethod(getPackageInfoMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkgName = param.args[0] as? String ?: return

                    // Controlliamo se è Spotify
                    if (pkgName.contains("spotify", ignoreCase = true)) {
                        val stackTrace = Exception().stackTrace.joinToString { it.className }

                        // Protezione SSL/Google: se la chiamata viene da GMS o Conscrypt, non tocchiamo nulla
                        if (!stackTrace.contains("com.google.android.gms") && !stackTrace.contains("org.conscrypt")) {
                            val info = param.result as? PackageInfo ?: return
                            val fakeSignature = Signature(hexToBytes(SPOTIFY_SHA))

                            // Spoof classico
                            info.signatures = arrayOf(fakeSignature)

                            // Spoof moderno (Android 9+) per bypassare i check profondi
                            runCatching {
                                if (android.os.Build.VERSION.SDK_INT >= 28) {
                                    val signingInfoClass = Class.forName("android.content.pm.SigningInfo")
                                    val signingInfo = XposedHelpers.newInstance(signingInfoClass)
                                    XposedHelpers.setObjectField(info, "signingInfo", signingInfo)
                                }
                            }

                            param.result = info
                            XposedBridge.log("SPOOF: Firma sostituita con successo per $pkgName")
                        }
                    }
                }
            })
        }

        // 2. NEUTRALIZZAZIONE INTEGRITÀ (DexKit)
        runCatching { System.loadLibrary("dexkit") }
        thread {
            // Delay ridotto a 500ms: con oPatch dobbiamo essere veloci
            Thread.sleep(500)
            runCatching {
                DexKitBridge.create(apkPath).use { bridge ->
                    val integrityMethods = Fingerprints.findIntegrityCheck(bridge)
                    val protoMethods = Fingerprints.findIntegrityProto(bridge)

                    XposedBridge.log("SPOOF: Scansione completata. Check: ${integrityMethods.size}, Proto: ${protoMethods.size}")

                    (integrityMethods + protoMethods).forEach { methodData ->
                        runCatching {
                            val method = methodData.getMethodInstance(classLoader)
                            XposedBridge.log("SPOOF: Applicazione hook su -> ${method.declaringClass.name}.${method.name}")

                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val returnType = (param.method as java.lang.reflect.Method).returnType
                                    val methodName = param.method.name.lowercase()

                                    when {
                                        returnType == String::class.java -> {
                                            param.result = ""
                                            XposedBridge.log("SPOOF: Token neutralizzato in -> ${param.method.name}")
                                        }
                                        returnType == Boolean::class.javaPrimitiveType -> {
                                            param.result = !methodName.contains("error") && !methodName.contains("fail")
                                            XposedBridge.log("SPOOF: Booleano forzato in -> ${param.method.name} a ${param.result}")
                                        }
                                        returnType == Int::class.javaPrimitiveType -> {
                                            param.result = 0
                                        }
                                        returnType == Void.TYPE -> {
                                            param.result = null
                                        }
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