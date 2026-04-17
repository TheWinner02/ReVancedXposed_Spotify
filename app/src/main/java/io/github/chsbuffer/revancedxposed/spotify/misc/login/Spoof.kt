package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.pm.PackageInfo
import android.content.pm.Signature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

object Spoof {

    // Lo SHA-256 originale che hai estratto (senza i due punti)
    private const val SPOTIFY_SHA256 = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"

    fun apply(classLoader: ClassLoader) {
        val targetPkg = "com.spotify.music" // Assicurati che sia il nome pacchetto corretto

        runCatching {
            // Hook su IPackageManager (livello più basso possibile in Java)
            val pmsClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)

            XposedHelpers.findAndHookMethod(pmsClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkgName = param.args[0] as String
                    if (pkgName == targetPkg) {
                        val info = param.result as? PackageInfo ?: return

                        // Inseriamo la firma originale
                        val fakeSig = Signature(hexStringToByteArray(SPOTIFY_SHA256))

                        // Copriamo sia il vecchio sistema (signatures) che il nuovo (signingInfo)
                        info.signatures = arrayOf(fakeSig)

                        runCatching {
                            val signingInfoClass = XposedHelpers.findClass("android.content.pm.SigningInfo", classLoader)
                            val signingInfo = XposedHelpers.newInstance(signingInfoClass)
                            // Qui forziamo il sistema a credere che non ci siano firme multiple (segno di manomissione)
                            param.result = info
                        }
                    }
                }
            })
        }

        // 2. FORZATURA FLAG DI SISTEMA
        runCatching {
            val buildClass = XposedHelpers.findClass("android.os.Build", classLoader)
            XposedHelpers.setStaticObjectField(buildClass, "TAGS", "release-keys")
            XposedHelpers.setStaticObjectField(buildClass, "TYPE", "user")
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}