package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.security.cert.X509Certificate

object Spoof {

    private const val SPOTIFY_SHA256 = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"

    @SuppressLint("PackageManagerGetSignatures")
    fun apply(classLoader: ClassLoader) {
        val targetPkg = "com.spotify.music"

        // ==========================================
        // 1. REPLICA: SpoofSignaturePatch & SpoofPackageInfoPatch
        // ==========================================
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)

            XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args[0] as String == targetPkg) {
                        val flags = param.args[1] as Int
                        val info = param.result as? PackageInfo ?: return
                        val fakeSig = Signature(hexStringToByteArray(SPOTIFY_SHA256))

                        if (flags and 64 != 0) {
                            @Suppress("DEPRECATION")
                            info.signatures = arrayOf(fakeSig)
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && flags and 0x08000000 != 0) {
                            runCatching {
                                val signingInfoClass = Class.forName("android.content.pm.SigningInfo")
                                val signingInfo = XposedHelpers.newInstance(signingInfoClass)
                                val mSigningDetails = XposedHelpers.getObjectField(signingInfo, "mSigningDetails")
                                XposedHelpers.setObjectField(mSigningDetails, "signatures", arrayOf(fakeSig))
                                XposedHelpers.setObjectField(info, "signingInfo", signingInfo)
                            }
                        }
                        param.result = info
                    }
                }
            })

            // Installer Spoofing (Fondamentale)
            XposedHelpers.findAndHookMethod(pmClass, "getInstallerPackageName", String::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args[0] == targetPkg) param.result = "com.android.vending"
                }
            })
        }

        // ==========================================
        // 2. REPLICA: SpoofClientPatch (Livello Sistema)
        // Mimetizzazione Hardware/OS per ridurre i controlli lato server
        // ==========================================
        runCatching {
            XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", "release-keys")
            XposedHelpers.setStaticObjectField(Build::class.java, "TYPE", "user")
            XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "Apple")
            XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", "iPhone16,1")
        }

        // ==========================================
        // 3. BYPASS SSL / CONSCRYPT (Per l'errore visto nel log)
        // Impedisce a Spotify di rilevare le modifiche di rete
        // ==========================================
        runCatching {
            // Cerchiamo il TrustManager di base di Android
            val trustManagerClass = XposedHelpers.findClass("com.android.org.conscrypt.TrustManagerImpl", classLoader)
            XposedHelpers.findAndHookMethod(trustManagerClass, "checkTrustedRecursive",
                Array<X509Certificate>::class.java, String::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, ByteArray::class.java, ByteArray::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Restituiamo una lista vuota per far credere che la catena SSL sia fidata
                        param.result = emptyList<X509Certificate>()
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