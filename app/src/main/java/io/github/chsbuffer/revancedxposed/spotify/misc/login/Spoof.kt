package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.security.cert.X509Certificate

object Spoof {

    private const val SPOTIFY_SHA = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"
    private var sessionToken: String? = null

    // 1. Inizializza lo scudo (Firma, SSL, Native) - Da chiamare in handleLoadPackage
    fun init(classLoader: ClassLoader) {

        // --- KILL NATIVE SECURITY ---
        runCatching {
            XposedHelpers.findAndHookMethod(Runtime::class.java, "loadLibrary0", Class::class.java, String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val lib = param.args[1] as String
                    if (listOf("orbit", "penguin", "puffin").any { lib.contains(it) }) {
                        XposedBridge.log("SPOOF: Bloccata libreria nativa spia: $lib")
                        param.result = null
                    }
                }
            })
        }

        // --- SPOOF SIGNATURE & SIGNING INFO ---
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkg = param.args[0] as String
                    if (pkg.contains("spotify")) {
                        val info = param.result as? PackageInfo ?: return
                        val fakeSig = Signature(hexToBytes(SPOTIFY_SHA))

                        // Supporto vecchio (signatures)
                        info.signatures = arrayOf(fakeSig)

                        // Supporto nuovo (signingInfo) per Android 9+
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val signingInfo = XposedHelpers.newInstance(SigningInfo::class.java)
                            XposedHelpers.setObjectField(info, "signingInfo", signingInfo)
                        }
                        param.result = info
                    }
                }
            })
        }

        // --- BYPASS SSL PINNING (Flessibile) ---
        runCatching {
            val trustManager = XposedHelpers.findClass("com.android.org.conscrypt.TrustManagerImpl", classLoader)
            // Cerchiamo il metodo checkTrustedRecursive indipendentemente dal numero di parametri
            val methods = trustManager.declaredMethods
            methods.filter { it.name == "checkTrustedRecursive" }.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = emptyList<X509Certificate>()
                    }
                })
            }
        }

        // --- PREFERENCES HOOK ---
        runCatching {
            val prefsClass = XposedHelpers.findClass("android.app.SharedPreferencesImpl", classLoader)
            XposedHelpers.findAndHookMethod(prefsClass, "getString", String::class.java, String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as String
                    when (key) {
                        "sp_dc", "login_token" -> if (sessionToken != null) param.result = sessionToken
                        "country", "last_registered_country" -> param.result = "US"
                        "product_type", "type" -> param.result = "premium"
                    }
                }
            })
        }
    }

    // 2. Imposta il token quando disponibile - Da chiamare in inContext
    fun setToken(token: String?) {
        this.sessionToken = token
        if (token != null) XposedBridge.log("SPOOF: Session Token iniettato nel motore.")
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}