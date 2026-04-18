package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.Signature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.security.cert.X509Certificate

object Spoof {

    private const val SPOTIFY_SHA256 = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"

    @SuppressLint("PackageManagerGetSignatures")
    fun apply(classLoader: ClassLoader, capturedToken: String?) {
        val targetPkg = "com.spotify.music"

        // --- PARTE 1: IDENTITÀ (Fondamentale per non essere bannati/bloccati) ---

        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)

            // Spoof della Firma (Replica SpoofSignaturePatch)
            XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args[0] == targetPkg) {
                        val flags = param.args[1] as Int
                        val info = param.result as? PackageInfo ?: return
                        val fakeSig = Signature(hexStringToByteArray(SPOTIFY_SHA256))
                        if (flags and 64 != 0) info.signatures = arrayOf(fakeSig)
                        param.result = info
                    }
                }
            })
        }

        // Bypass SSL/Conscrypt (Evita l'errore "MOVE ioctl" dei log)
        runCatching {
            val trustManagerClass = XposedHelpers.findClass("com.android.org.conscrypt.TrustManagerImpl", classLoader)
            XposedHelpers.findAndHookMethod(trustManagerClass, "checkTrustedRecursive",
                Array<X509Certificate>::class.java, String::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, ByteArray::class.java, ByteArray::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = emptyList<X509Certificate>()
                    }
                })
        }

        // --- PARTE 2: SESSIONE & BYPASS 14 GIORNI (Quello che hai scritto tu) ---

        // Iniezione Token nelle SharedPreferences
        if (!capturedToken.isNullOrEmpty()) {
            runCatching {
                val prefsClass = XposedHelpers.findClass("android.app.SharedPreferencesImpl", classLoader)
                XposedHelpers.findAndHookMethod(prefsClass, "getString", String::class.java, String::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as String
                        if (key == "sp_dc" || key == "login_token") {
                            param.result = capturedToken
                        }
                    }
                })
            }
        }

        // Forza Paese US e Stato LoggedIn
        runCatching {
            val userClass = XposedHelpers.findClass("com.spotify.user.UserAttributes", classLoader)
            XposedHelpers.findAndHookMethod(userClass, "getCountry", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = "US" }
            })
        }

        runCatching {
            val sessionClass = XposedHelpers.findClass("com.spotify.authentication.login.LoginState", classLoader)
            XposedHelpers.findAndHookMethod(sessionClass, "isLoggedIn", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!capturedToken.isNullOrEmpty()) param.result = true
                }
            })
        }
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}