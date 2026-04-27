package io.github.chsbuffer.revancedxposed

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import app.revanced.extension.shared.Utils
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.IXposedHookZygoteInit.StartupParam
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.chsbuffer.revancedxposed.spotify.AdBlockHook
import io.github.chsbuffer.revancedxposed.spotify.RoundyUIHook
import io.github.chsbuffer.revancedxposed.spotify.SettingsSheet
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import io.github.chsbuffer.revancedxposed.spotify.ThemeHook
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.view.isNotEmpty
import java.io.File
import android.os.Environment
import android.provider.Settings

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    lateinit var startupParam: StartupParam
    lateinit var lpparam: LoadPackageParam
    lateinit var app: Application
    val hooksByPackage = mapOf(
        "com.spotify.music" to { SpotifyHook(app, lpparam) },
    )

    private external fun setInternalApkPath(path: String)

    fun shouldHook(packageName: String): Boolean {
        // Supporto per la strategia Mochi: accetta il pacchetto originale e i cloni
        return packageName.startsWith("com.spotify.music")
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        XposedBridge.log("ReVancedXposed: handleLoadPackage called for ${lpparam.packageName}")

        if (!lpparam.isFirstApplication) return
        if (!shouldHook(lpparam.packageName)) return
        this.lpparam = lpparam

        if (lpparam.packageName.startsWith("com.spotify.music")) {
            try {
                // 1. Cerchiamo l'APK stock (Strategia Mochi)
                val originalApk = prepareOriginalApk(lpparam)
                val originalApkPath = originalApk?.absolutePath ?: File(lpparam.appInfo.dataDir, "cache/base.apk").absolutePath
                
                // 2. Applichiamo l'illusione Matrix (Firme e Stealth)
                spoofSignature(lpparam, originalApkPath)
                hideXposedFromStackTrace()
                bypassAndroidIdRestriction(lpparam)
                bypassGmsIntegrity(lpparam, originalApkPath)
                XposedBridge.log("ReVancedXposed: Stealth and GMS bypasses initialized")

                // 3. Carichiamo la libreria nativa Dobby per il redirect dei file
                XposedBridge.log("ReVancedXposed: Attempting to load native library for Spotify...")
                try {
                    System.loadLibrary("revancedxposed")
                    if (originalApk != null) {
                        setInternalApkPath(originalApk.absolutePath)
                    }
                    XposedBridge.log("ReVancedXposed: Native library 'revancedxposed' loaded successfully")
                } catch (e: UnsatisfiedLinkError) {
                    XposedBridge.log("ReVancedXposed: Native library not found: ${e.message}")
                } catch (e: Throwable) {
                    XposedBridge.log("ReVancedXposed: Error loading native library: ${e.message}")
                }
            } catch (e: Throwable) {
                XposedBridge.log("ReVancedXposed: Initialization failed: ${e.message}")
            }
        }

        // Trigger UI: Long Click su Avatar per aprire le impostazioni
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onPostCreate",
            android.os.Bundle::class.java,
            object : XC_MethodHook() {
                @SuppressLint("DiscouragedApi")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (!activity.javaClass.name.contains("MainActivity")) return

                    val decorView = activity.window.decorView as ViewGroup
                    decorView.viewTreeObserver.addOnGlobalLayoutListener {
                        val avatarIds = listOf("profile_button", "profile_image", "avatar", "user_avatar", "faceview", "faceheader_image")
                        var found = false

                        for (idName in avatarIds) {
                            val resId = activity.resources.getIdentifier(idName, "id", activity.packageName)
                            if (resId != 0) {
                                val avatarView = activity.findViewById<View>(resId)
                                if (avatarView != null && !found) {
                                    setModLongClickListener(avatarView, activity)
                                    found = true
                                }
                            }
                        }
                        if (!found) findAvatarRecursive(decorView, activity)
                    }
                }
            }
        )

        inContext(lpparam) { app ->
            this.app = app
            val prefs = app.getSharedPreferences("spotify_prefs", 0)

            /*
            if (isReVancedPatched(lpparam)) {
                Utils.showToastLong("ReVanced Xposed FE module does not work with patched app")
                return@inContext
            }
            */
            Utils.showToastLong("ReVanced Xposed FE is initializing, please wait...")

            // --- ATTIVAZIONE HOOK ---
            try {
                if (prefs.getBoolean("enable_premium", false)) {
                    // Usiamo sempre la factory di Spotify originale anche per i cloni
                    hooksByPackage[lpparam.packageName]?.invoke()?.Hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Premium fallita: ${e.message}")
            }

            try {
                if (prefs.getBoolean("enable_adblock", false)) {
                    AdBlockHook(lpparam).hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("AdBlocker fallito: ${e.message}")
            }

            try {
                if (prefs.getBoolean("enable_monet", false)) {
                    ThemeHook(app, lpparam).hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Monet fallita: ${e.message}")
            }

            try {
                if (prefs.getBoolean("enable_round_ui", false)) {
                    RoundyUIHook(lpparam).hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Roundy fallita: ${e.message}")
            }
        }
    }

    @SuppressLint("SdCardPath", "SetWorldReadable")
    private fun prepareOriginalApk(lpparam: LoadPackageParam): File? {
        val stockPkg = "com.spotify.music"
        XposedBridge.log("ReVancedXposed: Searching for stock APK ($stockPkg)...")

        if (lpparam.packageName != stockPkg) {
            // Step 1: IPackageManager (ActivityThread)
            try {
                val activityThreadClass = XposedHelpers.findClassIfExists("android.app.ActivityThread", null)
                val sPackageManager = if (activityThreadClass != null) XposedHelpers.getStaticObjectField(activityThreadClass, "sPackageManager") else null
                
                if (sPackageManager != null) {
                    val userId = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.os.UserHandle", null), "myUserId") as Int
                    val appInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        XposedHelpers.callMethod(sPackageManager, "getApplicationInfo", stockPkg, 0L, userId)
                    } else {
                        XposedHelpers.callMethod(sPackageManager, "getApplicationInfo", stockPkg, 0, userId)
                    } as? android.content.pm.ApplicationInfo

                    if (appInfo?.sourceDir != null && File(appInfo.sourceDir).exists()) {
                        XposedBridge.log("ReVancedXposed SUCCESS: Found stock APK via IPackageManager at ${appInfo.sourceDir}")
                        return File(appInfo.sourceDir)
                    }
                }
            } catch (e: Exception) { XposedBridge.log("ReVancedXposed Discovery Step 1 failure: ${e.message}") }

            // Step 2: pm path (Shell)
            try {
                val process = Runtime.getRuntime().exec("pm path $stockPkg")
                val line = process.inputStream.bufferedReader().readLine()
                if (line != null && line.startsWith("package:")) {
                    val path = line.substring(8).trim()
                    if (File(path).exists()) {
                        XposedBridge.log("ReVancedXposed SUCCESS: Found stock APK via Shell at $path")
                        return File(path)
                    }
                }
            } catch (e: Exception) { XposedBridge.log("ReVancedXposed Discovery Step 2 failure: ${e.message}") }

            // Step 3: Brute Force Ricorsivo (Per Android 11+)
            try {
                val appDir = File("/data/app")
                if (appDir.exists() && appDir.isDirectory) {
                    appDir.walkTopDown().maxDepth(3).forEach { file ->
                        if (file.name.contains(stockPkg) && file.isDirectory) {
                            val apk = File(file, "base.apk")
                            if (apk.exists()) {
                                XposedBridge.log("ReVancedXposed SUCCESS: Found stock APK via Brute-Force at ${apk.absolutePath}")
                                return apk
                            }
                        }
                    }
                }
            } catch (e: Exception) { XposedBridge.log("ReVancedXposed Discovery Step 3 failure: ${e.message}") }
        }
        
        // Fallback Finale: Download o Cache
        val potentialPaths = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "base.apk"),
            File("/sdcard/Download/base.apk"),
            File("/sdcard/Android/media/$stockPkg/base.apk")
        )
        return potentialPaths.find { it.exists() && it.canRead() }
    }

    private fun spoofSignature(lpparam: LoadPackageParam, originalApkPath: String) {
        val stockPkg = "com.spotify.music"
        val currentPkg = lpparam.packageName
        val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", lpparam.classLoader)
        
        val pmHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val pkgName = param.args[0] as String
                if (pkgName != currentPkg && pkgName != stockPkg) return
                val info = param.result as? PackageInfo ?: return
                applyOriginalSignature(info, originalApkPath, param.thisObject as PackageManager)
            }
        }
        XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, pmHook)
        try {
            val flagsClass = XposedHelpers.findClass("android.content.pm.PackageManager\$PackageInfoFlags", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, flagsClass, pmHook)
        } catch (ignored: Throwable) {}

        // Deep Hook IPackageManager
        try {
            val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader)
            val sPackageManagerField = XposedHelpers.findField(activityThreadClass, "sPackageManager")
            val originalProxy = sPackageManagerField.get(null)
            if (originalProxy != null) {
                XposedBridge.hookAllMethods(originalProxy.javaClass, "getPackageInfo", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pkgName = param.args[0] as? String ?: return
                        if (pkgName != currentPkg && pkgName != stockPkg) return
                        val info = param.result as? PackageInfo ?: return
                        val context = XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? android.content.Context
                        val pm = context?.packageManager ?: (param.thisObject as? PackageManager)
                        if (pm != null) applyOriginalSignature(info, originalApkPath, pm)
                    }
                })
            }
        } catch (e: Throwable) {}
    }

    private fun applyOriginalSignature(info: PackageInfo, originalApkPath: String, pm: PackageManager) {
        try {
            val fmi = pm.getPackageArchiveInfo(originalApkPath, PackageManager.GET_SIGNATURES)
            val originalSignatures = fmi?.signatures
            if (!originalSignatures.isNullOrEmpty()) {
                info.signatures = originalSignatures
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    try {
                        val signingInfoClass = XposedHelpers.findClass("android.content.pm.SigningInfo", pm.javaClass.classLoader)
                        val signingInfo = XposedHelpers.newInstance(signingInfoClass)
                        XposedHelpers.setObjectField(info, "signingInfo", signingInfo)
                    } catch (ignored: Exception) {}
                }
                XposedBridge.log("ReVancedXposed: Spoofed signatures from $originalApkPath")
            }
        } catch (e: Exception) { XposedBridge.log("ReVancedXposed Error applying signatures: ${e.message}") }
    }

    private fun hideXposedFromStackTrace() {
        val stealthHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val stackTrace = param.result as? Array<*> ?: return
                val filteredList = mutableListOf<StackTraceElement>()
                var modified = false
                for (element in stackTrace) {
                    if (element is StackTraceElement) {
                        val className = element.className.lowercase()
                        if (className.contains("xposed") || className.contains("lsposed") || 
                            className.contains("revanced") || className.contains("chsbuffer")) {
                            modified = true
                            continue
                        }
                        filteredList.add(element)
                    }
                }
                if (modified) param.result = filteredList.toTypedArray()
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(Throwable::class.java, "getStackTrace", stealthHook)
            XposedHelpers.findAndHookMethod(Thread::class.java, "getStackTrace", stealthHook)
        }
    }

    private fun bypassGmsIntegrity(lpparam: LoadPackageParam, originalApkPath: String) {
        val stockPkg = "com.spotify.music"
        val currentPkg = lpparam.packageName
        XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", lpparam.classLoader, "getPackageInfo", 
            String::class.java, Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val pkgName = param.args[0] as? String ?: return
                if (pkgName == currentPkg || pkgName == stockPkg) {
                    val info = param.result as? PackageInfo ?: return
                    applyOriginalSignature(info, originalApkPath, param.thisObject as PackageManager)
                }
            }
        })
    }

    private fun bypassAndroidIdRestriction(lpparam: LoadPackageParam) {
        XposedHelpers.findAndHookMethod("android.provider.Settings\$Secure", lpparam.classLoader, "getString", 
            android.content.ContentResolver::class.java, String::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[1] == Settings.Secure.ANDROID_ID) {
                    param.result = "8888888888888888"
                    XposedBridge.log("ReVancedXposed: Spoofed ANDROID_ID for Spotify")
                }
            }
        })
    }

    private fun setModLongClickListener(view: View, activity: Activity) {
        if (view.tag == "mod_hooked") return
        view.tag = "mod_hooked"
        view.setOnLongClickListener {
            val realView = if (it is ViewGroup && it.isNotEmpty()) it.getChildAt(0) else it
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            SettingsSheet.show(activity, realView)
            true
        }
    }

    private fun findAvatarRecursive(view: View, activity: Activity) {
        if (view is ImageView || view.contentDescription?.toString()?.contains("Profilo", true) == true) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            if (location[0] < 150 && location[1] < 200 && view.width > 0) {
                setModLongClickListener(view, activity)
                return
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findAvatarRecursive(view.getChildAt(i), activity)
        }
    }

    private fun isReVancedPatched(lpparam: LoadPackageParam): Boolean {
        val utilsClasses = listOf(
            "app.revanced.extension.shared.Utils",
            "app.revanced.extension.shared.utils.Utils",
            "app.revanced.integrations.shared.Utils"
        )
        return utilsClasses.any { runCatching { lpparam.classLoader.loadClass(it) }.isSuccess }
    }

    override fun initZygote(startupParam: StartupParam) {
        this.startupParam = startupParam
        XposedInit = startupParam
    }
}

fun inContext(lpparam: LoadPackageParam, f: (Application) -> Unit) {
    val appClazz = XposedHelpers.findClass(lpparam.appInfo.className, lpparam.classLoader)
    XposedBridge.hookMethod(appClazz.getMethod("onCreate"), object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val app = param.thisObject as Application
            Utils.setContext(app)
            f(app)
        }
    })
}
