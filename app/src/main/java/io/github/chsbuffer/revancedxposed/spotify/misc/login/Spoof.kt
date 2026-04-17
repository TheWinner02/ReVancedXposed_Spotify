package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.pm.PackageInfo
import android.content.pm.Signature
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

object Spoof {

    fun applyAll(classLoader: ClassLoader) {
        val targetPkg = "com.spotify.music" // Nome pacchetto standard

        // 1. STACKTRACE OBFUSCATOR (Invisibilità codice)
        runCatching {
            XposedHelpers.findAndHookMethod(StackTraceElement::class.java, "getClassName", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val className = param.result as String? ?: return
                    if (className.contains("xposed", true) || className.contains("revanced", true) ||
                        className.contains("chsbuffer", true) || className.contains("patch", true)) {
                        param.result = "android.app.ActivityThread"
                    }
                }
            })
        }

        // 2. SIGNATURE SPOOFING (Firma Originale Spotify)
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkgName = param.args[0] as String
                    val flags = param.args[1] as Int
                    if (pkgName == targetPkg && (flags and android.content.pm.PackageManager.GET_SIGNATURES != 0)) {
                        val packageInfo = param.result as PackageInfo
                        // Firma fake (Hash tipico Spotify)
                        val fakeSig = Signature("308202...vostro_hash_reale...")
                        packageInfo.signatures = arrayOf(fakeSig)
                        param.result = packageInfo
                    }
                }
            })
        }

        // 3. INSTALLER SPOOFING (Fingiamo il Play Store)
        runCatching {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args[0] == targetPkg) param.result = "com.android.vending"
                }
            }
            XposedHelpers.findAndHookMethod(pmClass, "getInstallerPackageName", String::class.java, hook)
        }

        // 4. DEVICE SPOOFING (Fingiamo un Pixel 8)
        runCatching {
            val buildClass = XposedHelpers.findClass("android.os.Build", classLoader)
            XposedHelpers.setStaticObjectField(buildClass, "MANUFACTURER", "Google")
            XposedHelpers.setStaticObjectField(buildClass, "MODEL", "Pixel 8")
            XposedHelpers.setStaticObjectField(buildClass, "TAGS", "release-keys")
        }

        // 5. FILESYSTEM HIDER (Per oPatch)
        runCatching {
            XposedHelpers.findAndHookMethod(File::class.java, "exists", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val name = (param.thisObject as File).name
                    if (name.contains("libpatch", true) || name.contains("xposed", true)) {
                        param.result = false
                    }
                }
            })
        }
    }
}