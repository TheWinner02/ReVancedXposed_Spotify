package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.pm.PackageInfo
import android.content.pm.Signature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

object Spoof {

    // L'hash SHA-256 che hai trovato
    private const val SPOTIFY_SHA256 = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"

    fun apply(classLoader: ClassLoader) {
        val targetPkg = "com.spotify.music" // Usa il package name reale qui

        // 1. SIGNATURE SPOOFING (Il pezzo forte)
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkgName = param.args[0] as String
                    val flags = param.args[1] as Int

                    // Se chiedono le firme (flag 64) per Spotify
                    if (pkgName == targetPkg && (flags and 64 != 0)) {
                        val info = param.result as PackageInfo
                        // Creiamo la firma fake usando l'hash convertito
                        val fakeSig = Signature(hexStringToByteArray(SPOTIFY_SHA256))
                        info.signatures = arrayOf(fakeSig)
                        param.result = info
                    }
                }
            })
        }

        // 2. INSTALLER SPOOFING (Per Play Integrity)
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            XposedHelpers.findAndHookMethod(pmClass, "getInstallerPackageName", String::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args[0] == targetPkg) param.result = "com.android.vending"
                }
            })
        }

        // 3. BUILD & DEVICE SPOOFING (Per ReCaptcha)
        runCatching {
            val buildClass = XposedHelpers.findClass("android.os.Build", classLoader)
            XposedHelpers.setStaticObjectField(buildClass, "MANUFACTURER", "Google")
            XposedHelpers.setStaticObjectField(buildClass, "MODEL", "Pixel 8")
            XposedHelpers.setStaticObjectField(buildClass, "TAGS", "release-keys")
            XposedHelpers.setStaticObjectField(buildClass, "TYPE", "user")
        }
    }

    // Helper per convertire la stringa SHA-256 in Byte Array per la classe Signature
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