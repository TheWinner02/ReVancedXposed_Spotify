package io.github.chsbuffer.revancedxposed

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
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
import androidx.core.view.isNotEmpty
import android.os.Environment
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
import java.io.File

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private var isInitialized = false
    
    lateinit var startupParam: StartupParam
    lateinit var lpparam: LoadPackageParam
    lateinit var app: Application
    var targetPackageName: String? = null
    val hooksByPackage = mapOf(
        "com.spotify.music" to { SpotifyHook(app, lpparam) },
    )

    private external fun setInternalApkPath(path: String)

    fun shouldHook(packageName: String): Boolean {
        if (!hooksByPackage.containsKey(packageName)) return false
        if (targetPackageName == null) targetPackageName = packageName
        return targetPackageName == packageName
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (isInitialized) return
        isInitialized = true

        XposedBridge.log("Chimera: Entering process ${lpparam.packageName}")
        
        if (!lpparam.isFirstApplication) return
        if (!shouldHook(lpparam.packageName)) return
        this.lpparam = lpparam

        // --- PHASE 1: NATIVE GHOST SHIELD ---
        if (lpparam.packageName == "com.spotify.music") {
            try {
                System.loadLibrary("ghost")
                XposedBridge.log("Chimera: Native Ghost Shield active")
            } catch (e: Throwable) {
                XposedBridge.log("Chimera: Failed to load native shield: ${e.message}")
            }
        }

        // --- PHASE 2: UI & DYNAMIC ENGINE BOOTSTRAP ---
        inContext(lpparam) { context ->
            this.app = context
            
            if (lpparam.packageName == "com.spotify.music") {
                // Handle Stock APK Escrow for Dobby
                val internalApk = prepareOriginalApk(lpparam)
                if (internalApk != null) {
                    runCatching { setInternalApkPath(internalApk.absolutePath) }
                }
                
                // Advanced Stealth & GMS Bypasses
                spoofSignature(lpparam)
                hideXposedFromStackTrace()
                bypassAndroidIdRestriction(lpparam)
                bypassGmsIntegrity(lpparam)
                
                // Bootstrap the dynamic engine
                ChimeraEngine.bootstrap(context)
            }

            val prefs = context.getSharedPreferences("spotify_prefs", 0)
            XposedBridge.log("Chimera: Initializing legacy hook chain...")
            
            if (isReVancedPatched(lpparam)) {
                Utils.showToastLong("ReVanced Xposed FE module does not work with patched app")
                return@inContext
            }

            try {
                if (prefs.getBoolean("enable_premium", true)) {
                    hooksByPackage[lpparam.packageName]?.invoke()?.Hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Premium fallita: ${e.message}")
            }

            try {
                if (prefs.getBoolean("enable_adblock", true)) {
                    AdBlockHook(lpparam).hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("AdBlocker fallito: ${e.message}")
            }

            try {
                if (prefs.getBoolean("enable_monet", true)) {
                    ThemeHook(app, lpparam).hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Monet fallita: ${e.message}")
            }

            try {
                if (prefs.getBoolean("enable_round_ui", true)) {
                    RoundyUIHook(lpparam).hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Roundy fallita: ${e.message}")
            }
        }
    }

    @SuppressLint("SdCardPath", "SetWorldReadable")
    private fun prepareOriginalApk(lpparam: LoadPackageParam): File? {
        val internalApk = File(lpparam.appInfo.dataDir, "cache/stock.apk")
        val publicApk = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "base.apk")

        if (publicApk.exists() && (!internalApk.exists() || publicApk.lastModified() > internalApk.lastModified())) {
            XposedBridge.log("Chimera: Syncing stock APK to internal cache...")
            try {
                internalApk.parentFile?.mkdirs()
                publicApk.copyTo(internalApk, overwrite = true)
                internalApk.setReadable(true, false)
            } catch (e: Exception) {
                XposedBridge.log("Chimera: Sync failed -> ${e.message}")
            }
        }
        return if (internalApk.exists()) internalApk else null
    }

    private fun spoofSignature(lpparam: LoadPackageParam) {
        val targetPkg = "com.spotify.music"
        val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", lpparam.classLoader)
        val pmHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val pkgName = param.args[0] as String
                if (pkgName != targetPkg) return
                val info = param.result as? PackageInfo ?: return
                val internalApk = File(lpparam.appInfo.dataDir, "cache/stock.apk")
                if (internalApk.exists()) {
                    applyOriginalSignature(info, internalApk.absolutePath, param.thisObject as PackageManager)
                }
            }
        }
        XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, pmHook)
        try {
            val flagsClass = XposedHelpers.findClass("android.content.pm.PackageManager\$PackageInfoFlags", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, flagsClass, pmHook)
        } catch (ignored: Throwable) {}
    }

    private fun bypassGmsIntegrity(lpparam: LoadPackageParam) {
        try {
            val logClass = XposedHelpers.findClass("android.util.Log", lpparam.classLoader)
            XposedBridge.hookAllMethods(logClass, "w", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val tag = param.args[0] as? String ?: return
                    if (tag.contains("Gservices") || tag.contains("FA-SVC")) param.result = 0
                }
            })
        } catch (ignored: Throwable) {}
    }

    private fun bypassAndroidIdRestriction(lpparam: LoadPackageParam) {
        XposedHelpers.findAndHookMethod("android.provider.Settings\$Secure", lpparam.classLoader, "getString", 
            android.content.ContentResolver::class.java, String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[1] == Settings.Secure.ANDROID_ID) {
                        param.result = "8888888888888888"
                        XposedBridge.log("ReVancedXposed: Spoofed ANDROID_ID")
                    }
                }
            })
    }

    private fun applyOriginalSignature(info: PackageInfo, originalApkPath: String, pm: PackageManager) {
        try {
            val fmi = pm.getPackageArchiveInfo(originalApkPath, PackageManager.GET_SIGNATURES)
            val originalSignatures = fmi?.signatures
            if (!originalSignatures.isNullOrEmpty()) {
                info.signatures = originalSignatures
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    try {
                        val signingInfo = XposedHelpers.newInstance(XposedHelpers.findClass("android.content.pm.SigningInfo", pm.javaClass.classLoader))
                        XposedHelpers.setObjectField(info, "signingInfo", signingInfo)
                    } catch (ignored: Exception) {}
                }
                XposedBridge.log("ReVancedXposed: Spoofed signatures from stock.apk")
            }
        } catch (e: Exception) {
            XposedBridge.log("ReVancedXposed: Signature error: ${e.message}")
        }
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
                            modified = true; continue
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

    private fun isReVancedPatched(lpparam: LoadPackageParam): Boolean {
        return runCatching {
            lpparam.classLoader.loadClass("app.revanced.extension.shared.Utils")
        }.isSuccess || runCatching {
            lpparam.classLoader.loadClass("app.revanced.extension.shared.utils.Utils")
        }.isSuccess || runCatching {
            lpparam.classLoader.loadClass("app.revanced.integrations.shared.Utils")
        }.isSuccess || runCatching {
            lpparam.classLoader.loadClass("app.revanced.integrations.shared.utils.Utils")
        }.isSuccess
    }

    override fun initZygote(startupParam: StartupParam) {
        this.startupParam = startupParam
        XposedInit = startupParam
    }

    companion object {
        @JvmStatic
        fun nativeBootstrap(context: Context) {
            // This is called by libghost.so after fileless memory injection
            // We use a Handler to avoid blocking the main thread during initialization
            // and to ensure the Spotify application context is fully stabilized.
            XposedBridge.log("Chimera: Static Native Bootstrap triggered")
            
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    XposedBridge.log("Chimera: Asynchronous Engine Activation...")
                    ChimeraEngine.bootstrap(context)
                }.onFailure {
                    XposedBridge.log("Chimera: Bootstrap failure -> ${it.message}")
                }
            }, 100) // Small delay to let Spotify breathe
        }
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
