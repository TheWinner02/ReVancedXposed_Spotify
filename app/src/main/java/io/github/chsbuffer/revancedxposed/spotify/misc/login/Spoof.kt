package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.security.cert.X509Certificate

object Spoof {

    private const val SPOTIFY_SHA = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"
    private var sessionToken: String? = null

    fun init(classLoader: ClassLoader) {

        // --- BYPASS INTEGRITY (Invece di bloccare le lib, bypassiamo i check) ---
        runCatching {
            val buildClass = XposedHelpers.findClass("android.os.Build", classLoader)
            XposedHelpers.setStaticObjectField(buildClass, "MANUFACTURER", "Samsung")
            XposedHelpers.setStaticObjectField(buildClass, "MODEL", "SM-G998B")
            XposedHelpers.setStaticObjectField(buildClass, "PRODUCT", "starlte")
        }

        // --- SPOOF SIGNATURE (Necessario per il login) ---
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

        // --- SSL BYPASS (Corretto per evitare schermi neri di caricamento) ---
        runCatching {
            val trustManager = XposedHelpers.findClass("com.android.org.conscrypt.TrustManagerImpl", classLoader)
            val methods = trustManager.declaredMethods
            methods.filter { it.name == "checkTrustedRecursive" }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = emptyList<X509Certificate>()
                    }
                })
            }
        }

        // --- PREFERENCES HOOK (Il cuore del bypass) ---
        runCatching {
            val prefsClass = XposedHelpers.findClass("android.app.SharedPreferencesImpl", classLoader)
            XposedHelpers.findAndHookMethod(prefsClass, "getString", String::class.java, String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as String
                    when (key) {
                        "sp_dc", "login_token" -> if (!sessionToken.isNullOrEmpty()) param.result = sessionToken
                        "country", "last_registered_country" -> param.result = "US"
                        "product_type" -> param.result = "premium"
                    }
                }
            })

            // Fondamentale: forziamo anche i booleani per evitare il blocco 14 giorni
            XposedHelpers.findAndHookMethod(prefsClass, "getBoolean", String::class.java, Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as String
                    if (key.contains("travel", true) || key.contains("restriction", true)) {
                        param.result = false
                    }
                }
            })
        }
    }

    fun setToken(token: String?) {
        this.sessionToken = token
    }

    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}