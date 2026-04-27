package io.github.chsbuffer.revancedxposed

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
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
import android.os.Bundle
import android.os.Environment
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
                
                // Bootstrap the dynamic engine
                ChimeraEngine.bootstrap(context)
            }

            // Legacy hooks initialization (for stability)
            val prefs = context.getSharedPreferences("spotify_prefs", 0)
            
            XposedBridge.log("Chimera: Initializing hook chain...")
            
            // Re-integrate the profile long click trigger here if needed
            // ... (I will keep the rest of your original logic below)
            
            if (isReVancedPatched(lpparam)) {
                Utils.showToastLong("ReVanced Xposed FE module does not work with patched app")
                return@inContext
            }
            Utils.showToastLong("ReVanced Xposed FE is initializing, please wait...")

            // --- BLOCCO PREMIUM ---
            // Ora è isolato: se Roundy sopra crasha, questo verrà comunque eseguito!
            try {
                if (prefs.getBoolean("enable_premium", true)) {
                    hooksByPackage[lpparam.packageName]?.invoke()?.Hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Premium fallita: ${e.message}")
            }

            // --- BLOCCO: AD BLOCK ---
            try {
                // Puoi aggiungere "enable_adblock" nel tuo SettingsSheet più tardi
                if (prefs.getBoolean("enable_adblock", true)) {
                    AdBlockHook(lpparam).hook()
                    XposedBridge.log("AdBlocker: Modulo attivato")
                }
            } catch (e: Exception) {
                XposedBridge.log("AdBlocker fallito: ${e.message}")
            }

            // --- BLOCCO MONET ---
            try {
                if (prefs.getBoolean("enable_monet", true)) {
                    ThemeHook(app, lpparam).hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Monet fallita: ${e.message}")
            }

            // --- BLOCCO ROUNDY (Il sospettato numero 1) ---
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

    // Funzione per impostare il listener e dare feedback
    private fun setModLongClickListener(view: View, activity: Activity) {
        if (view.tag == "mod_hooked") return
        view.tag = "mod_hooked"

        view.setOnLongClickListener {
            // Se la view cliccata è un contenitore (ViewGroup), cerchiamo l'immagine dentro
            val realView = if (it is ViewGroup && it.isNotEmpty()) {
                it.getChildAt(0)
            } else {
                it
            }

            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            SettingsSheet.show(activity, realView)
            true
        }
    }

    // Cerca l'immagine profilo basandosi sulla posizione (Top-Left)
    private fun findAvatarRecursive(view: View, activity: Activity) {
        if (view is ImageView || view.contentDescription?.toString()?.contains("Profilo", true) == true) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            // L'avatar è solitamente entro i primi 150px dall'alto e 150px da sinistra
            if (location[0] < 150 && location[1] < 200 && view.width > 0) {
                setModLongClickListener(view, activity)
                return
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findAvatarRecursive(view.getChildAt(i), activity)
            }
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
            // This is called by libghost.so if statically injected
            XposedBridge.log("Chimera: Static Native Bootstrap triggered")
            ChimeraEngine.bootstrap(context)
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
