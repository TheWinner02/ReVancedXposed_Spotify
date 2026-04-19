package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge

object Spoof {

    private const val SPOTIFY_SHA = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"
    private const val RE_CLIENT_VERSION = "iphone-9.0.58.558.g200011c"
    private const val RE_HARDWARE = "iPhone16,1"
    private const val RE_SYSTEM = "17.7.2"
    private const val RE_USER_AGENT = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"

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
            XposedBridge.log("SPOOF: Firma bypassata.")
        }

        // 2. SPOOF DI SISTEMA (BUILD)
        runCatching {
            XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", RE_HARDWARE)
            XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", RE_HARDWARE)
            XposedHelpers.setStaticObjectField(Build.VERSION::class.java, "RELEASE", RE_SYSTEM)
            XposedBridge.log("SPOOF: Proprietà Build.MODEL impostate su iOS ($RE_HARDWARE)")
        }

        // 3. LOGICHE DEXKIT (Include User-Agent, OkHttp, Integrità e Versioni)
        kotlin.concurrent.thread {
            runCatching {
                System.loadLibrary("dexkit")
                XposedBridge.log("SPOOF: Inizio scansione DexKit...")

                DexKitBridge.create(apkPath).use { bridge ->

                    // --- SPOOF USER-AGENT DINAMICO ---
                    val uaMethodData = Fingerprints.findUserAgentSetter(bridge).firstOrNull()
                    uaMethodData?.let { data ->
                        runCatching {
                            val method = data.getMethodInstance(classLoader)
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    param.args[0] = RE_USER_AGENT
                                }
                            })
                            XposedBridge.log("SPOOF: User-Agent forzato tramite DexKit Match!")
                        }
                    }

                    // --- HOOK OKHTTP DINAMICO ---
                    val headerMethod = bridge.findMethod {
                        matcher {
                            name = "header"
                            paramTypes("java.lang.String", "java.lang.String")
                        }
                    }.firstOrNull()

                    headerMethod?.let { methodData ->
                        runCatching {
                            val method = methodData.getMethodInstance(classLoader)
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val name = param.args[0] as? String ?: return
                                    if (name.equals("User-Agent", ignoreCase = true)) {
                                        val value = param.args[1] as? String ?: ""
                                        if (value.contains("Android")) param.args[1] = RE_USER_AGENT
                                    }
                                }
                            })
                            XposedBridge.log("SPOOF: Hook OkHttp (Header) completato.")
                        }
                    }

                    // --- BYPASS INTEGRITÀ ---
                    val integrityMethods = Fingerprints.findIntegrityCheck(bridge)
                    integrityMethods.forEach { methodData ->
                        runCatching {
                            val method = methodData.getMethodInstance(classLoader)
                            XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(null)) // Restituisce void
                            XposedBridge.log("SPOOF: Integrità bypassata su -> ${method.name}")
                        }
                    }

                    // --- SPOOF METODI CLIENT ---
                    val targetMethods = listOf("getClientVersion", "getSystemVersion", "getHardwareMachine")
                    targetMethods.forEach { methodName ->
                        val methods = Fingerprints.findClientDataMethods(bridge, methodName)
                        methods.forEach { methodData ->
                            runCatching {
                                val method = methodData.getMethodInstance(classLoader)
                                val fakeValue = when(methodName) {
                                    "getClientVersion" -> RE_CLIENT_VERSION
                                    "getSystemVersion" -> RE_SYSTEM
                                    "getHardwareMachine" -> RE_HARDWARE
                                    else -> ""
                                }
                                XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(fakeValue))
                                XposedBridge.log("SPOOF: Patchato ${method.name} ($methodName) -> $fakeValue")
                            }
                        }
                    }
                }
            }.onFailure {
                XposedBridge.log("SPOOF ERROR: DexKit crash -> ${it.message}")
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}