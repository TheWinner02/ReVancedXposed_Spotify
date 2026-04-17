package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

object Spoof {

    private const val SPOTIFY_SHA256 = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"

    @SuppressLint("PackageManagerGetSignatures") // Silenzia l'avviso deprecation
    fun apply(classLoader: ClassLoader) {
        val targetPkg = "com.spotify.music"

        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)

            XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkgName = param.args[0] as String
                    val flags = param.args[1] as Int

                    if (pkgName == targetPkg) {
                        val info = param.result as? PackageInfo ?: return
                        // Passiamo direttamente la costante per evitare l'avviso sul parametro sempre uguale
                        val fakeSig = Signature(hexStringToByteArray(SPOTIFY_SHA256))

                        // 1. Vecchie firme (Sempre necessario per compatibilità)
                        if (flags and 64 != 0) {
                            @Suppress("DEPRECATION")
                            info.signatures = arrayOf(fakeSig)
                        }

                        // 2. Nuove firme SigningInfo (Android 9+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            if (flags and 0x08000000 != 0) {
                                runCatching {
                                    val signingInfoClass = Class.forName("android.content.pm.SigningInfo")
                                    val signingInfo = XposedHelpers.newInstance(signingInfoClass)
                                    val mSigningDetails = XposedHelpers.getObjectField(signingInfo, "mSigningDetails")
                                    XposedHelpers.setObjectField(mSigningDetails, "signatures", arrayOf(fakeSig))

                                    XposedHelpers.setObjectField(info, "signingInfo", signingInfo)
                                }
                            }
                        }
                        param.result = info
                    }
                }
            })
        }

        // Spoof Installer e Build
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            XposedHelpers.findAndHookMethod(pmClass, "getInstallerPackageName", String::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args[0] == targetPkg) param.result = "com.android.vending"
                }
            })

            // Spoof dei parametri Build
            XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", "release-keys")
            XposedHelpers.setStaticObjectField(Build::class.java, "TYPE", "user")
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