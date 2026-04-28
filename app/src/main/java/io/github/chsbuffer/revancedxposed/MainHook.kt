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
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class MainHook {
    private var isInitialized = false
    lateinit var app: Application
    val hooksByPackage = mapOf(
        "com.spotify.music" to { SpotifyHook(app) },
    )

    private external fun setInternalApkPath(path: String)

    fun handleStandalone(context: Application) {
        if (isInitialized) return
        isInitialized = true

        log("Entering standalone process ${context.packageName}")
        this.app = context

        // --- PHASE 1: NATIVE GHOST SHIELD ---
        if (context.packageName == "com.spotify.music") {
            try {
                System.loadLibrary("ghost")
                log("Native Ghost Shield active")
            } catch (e: Throwable) {
                log("Failed to load native shield: ${e.message}")
            }
        }

        // --- PHASE 2: UI & DYNAMIC ENGINE BOOTSTRAP ---
        if (context.packageName == "com.spotify.music") {
            // Handle Stock APK Escrow for Dobby
            val internalApk = prepareOriginalApk(context)
            if (internalApk != null) {
                runCatching { setInternalApkPath(internalApk.absolutePath) }
            }
            
            // Advanced Stealth & GMS Bypasses
            hideXposedFromStackTrace()
            bypassAndroidIdRestriction(context)
            
            // Bootstrap the dynamic engine
            ChimeraEngine.bootstrap(context)
        }

        val prefs = context.getSharedPreferences("spotify_prefs", 0)
        log("Initializing legacy hook chain...")
        
        val hookConfigs = listOf(
            "enable_premium" to { hooksByPackage[context.packageName]?.invoke()?.Hook() },
            "enable_adblock" to { AdBlockHook(context.classLoader).hook() },
            "enable_monet" to { ThemeHook(context).hook() },
            "enable_round_ui" to { RoundyUIHook(context.classLoader).hook() }
        )

        hookConfigs.forEach { (prefKey, hookAction) ->
            try {
                if (prefs.getBoolean(prefKey, true)) {
                    hookAction()
                }
            } catch (e: Exception) {
                log("Hook $prefKey failed: ${e.message}")
            }
        }
    }

    @SuppressLint("SdCardPath", "SetWorldReadable")
    private fun prepareOriginalApk(context: Application): File? {
        val internalApk = File(context.cacheDir, "stock.apk")
        val publicApk = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "base.apk")

        if (publicApk.exists() && (!internalApk.exists() || publicApk.lastModified() > internalApk.lastModified())) {
            log("Syncing stock APK to internal cache...")
            try {
                internalApk.parentFile?.mkdirs()
                publicApk.copyTo(internalApk, overwrite = true)
                internalApk.setReadable(true, false)
            } catch (e: Exception) {
                log("Sync failed -> ${e.message}")
            }
        }
        return if (internalApk.exists()) internalApk else null
    }

    private fun bypassAndroidIdRestriction(context: Context) {
        try {
            val secureClass = XposedHelpers.findClass("android.provider.Settings\$Secure", context.classLoader)
            ChimeraBridge.hookMethod(
                XposedHelpers.findMethodExact(secureClass, "getString", android.content.ContentResolver::class.java, String::class.java),
                object : ChimeraBridge.XC_MethodHook() {
                    override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                        if (param.args?.get(1) == Settings.Secure.ANDROID_ID) {
                            param.setResult("8888888888888888")
                            log("Spoofed ANDROID_ID via ChimeraBridge")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            log("Failed to bypass Android ID: ${e.message}")
        }
    }

    private fun hideXposedFromStackTrace() {
        val stealthHook = object : ChimeraBridge.XC_MethodHook() {
            override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                val stackTrace = param.result as? Array<*> ?: return
                val filteredList = mutableListOf<StackTraceElement>()
                var modified = false
                
                val forbiddenTerms = listOf(
                    "xposed", "lsposed", "edxp", "sandhook", "epic", "whale",
                    "revanced", "chsbuffer", "chimera", "ghost"
                )

                for (element in stackTrace) {
                    if (element is StackTraceElement) {
                        val className = element.className.lowercase()
                        val methodName = element.methodName.lowercase()
                        
                        val isForbidden = forbiddenTerms.any { 
                            className.contains(it) || methodName.contains(it) 
                        }

                        if (isForbidden) {
                            modified = true
                            continue
                        }
                        filteredList.add(element)
                    }
                }
                
                if (modified) {
                    param.setResult(filteredList.toTypedArray())
                }
            }
        }
        runCatching {
            ChimeraBridge.hookMethod(Throwable::class.java.getDeclaredMethod("getStackTrace"), stealthHook)
            ChimeraBridge.hookMethod(Thread::class.java.getDeclaredMethod("getStackTrace"), stealthHook)
            
            ChimeraBridge.hookMethod(Throwable::class.java.getDeclaredMethod("toString"), object : ChimeraBridge.XC_MethodHook() {
                override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    val result = param.result as? String ?: return
                    val terms = listOf("xposed", "revanced", "chsbuffer", "chimera", "ghost")
                    if (terms.any { result.lowercase().contains(it) }) {
                        param.setResult(result.replace(Regex("(?i)(xposed|revanced|chsbuffer|chimera|ghost)"), "AndroidRuntime"))
                    }
                }
            })
        }.onFailure {
            log("Stealth hook failed: ${it.message}")
        }
    }

    companion object {
        private var isBootstrapped = false
        val instance = MainHook()

        fun log(message: String) {
            android.util.Log.i("Chimera:Main", message)
        }

        fun log(e: Throwable) {
            android.util.Log.e("Chimera:Main", "Error", e)
        }

        @JvmStatic
        fun nativeBootstrap(context: Context) {
            if (isBootstrapped) return
            isBootstrapped = true
            log("Static Native Bootstrap triggered")
            
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    log("Asynchronous Engine Activation...")
                    if (context is Application) {
                        instance.handleStandalone(context)
                    } else {
                        instance.handleStandalone(context.applicationContext as Application)
                    }
                }.onFailure {
                    log(it)
                }
            }, 100)
        }
    }
}

fun inContext(context: Application, f: (Application) -> Unit) {
    try {
        ChimeraBridge.hookMethod(
            context.javaClass.getDeclaredMethod("onCreate"),
            object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    val app = param.thisObject as Application
                    Utils.setContext(app)
                    f(app)
                }
            }
        )
    } catch (e: Throwable) {
        MainHook.log("Failed to hook onCreate: ${e.message}")
    }
}
