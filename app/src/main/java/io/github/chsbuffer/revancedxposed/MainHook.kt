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
        // Accetta com.spotify.music e qualsiasi variante clonata (es. com.spotify.music.mochi)
        return packageName.startsWith("com.spotify.music")
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        XposedBridge.log("ReVancedXposed: handleLoadPackage called for ${lpparam.packageName}")
        
        if (!lpparam.isFirstApplication) return
        if (!shouldHook(lpparam.packageName)) return
        this.lpparam = lpparam

        if (lpparam.packageName.startsWith("com.spotify.music")) {
            try {
                val internalApk = prepareOriginalApk(lpparam)
                spoofSignature(lpparam)
                hideXposedFromStackTrace()
                bypassAndroidIdRestriction(lpparam)
                bypassGmsIntegrity(lpparam)
                XposedBridge.log("ReVancedXposed: Stealth and GMS bypasses initialized")
                
                // Carica la libreria nativa per Spotify per attivare gli hook Dobby
                XposedBridge.log("ReVancedXposed: Attempting to load native library for Spotify...")
                try {
                    System.loadLibrary("revancedxposed")
                    if (internalApk != null) {
                        setInternalApkPath(internalApk.absolutePath)
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

        // --- NUOVO TRIGGER: LONG CLICK SU ICONA PROFILO ---
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onPostCreate", // Usiamo onPostCreate per essere sicuri che la UI sia pronta
            android.os.Bundle::class.java,
            object : XC_MethodHook() {
                @SuppressLint("DiscouragedApi")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (!activity.javaClass.name.contains("MainActivity")) return

                    // Spotify carica l'avatar in modo asincrono, aspettiamo che la vista sia disposta
                    val decorView = activity.window.decorView as ViewGroup
                    decorView.viewTreeObserver.addOnGlobalLayoutListener {
                        // Proviamo a trovare l'avatar tramite ID comuni
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

                        // Se non troviamo l'ID, cerchiamo la prima ImageView in alto a sinistra
                        if (!found) {
                            findAvatarRecursive(decorView, activity)
                        }
                    }
                }
            }
        )

        inContext(lpparam) { app ->
            this.app = app

            // Carichiamo le preferenze una volta sola
            val prefs = app.getSharedPreferences("spotify_prefs", 0)

            if (isReVancedPatched(lpparam)) {
                Utils.showToastLong("ReVanced Xposed FE module does not work with patched app")
                return@inContext
            }
            Utils.showToastLong("ReVanced Xposed FE is initializing, please wait...")

            // --- BLOCCO PREMIUM ---
            // Ora è isolato: se Roundy sopra crasha, questo verrà comunque eseguito!
            try {
                if (prefs.getBoolean("enable_premium", false)) {
                    hooksByPackage[lpparam.packageName]?.invoke()?.Hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Premium fallita: ${e.message}")
            }

            // --- BLOCCO: AD BLOCK ---
            try {
                // Puoi aggiungere "enable_adblock" nel tuo SettingsSheet più tardi
                if (prefs.getBoolean("enable_adblock", false)) {
                    AdBlockHook(lpparam).hook()
                    XposedBridge.log("AdBlocker: Modulo attivato")
                }
            } catch (e: Exception) {
                XposedBridge.log("AdBlocker fallito: ${e.message}")
            }

            // --- BLOCCO MONET ---
            try {
                if (prefs.getBoolean("enable_monet", false)) {
                    ThemeHook(app, lpparam).hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Monet fallita: ${e.message}")
            }

            // --- BLOCCO ROUNDY (Il sospettato numero 1) ---
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
        val internalApk = File(lpparam.appInfo.dataDir, "cache/base.apk")
        val stockPkg = "com.spotify.music"

        // 1. Prova a prelevare l'APK direttamente dall'app stock installata (Strategia Mochi)
        if (lpparam.packageName != stockPkg) {
            try {
                val context = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication") as? android.content.Context
                val pm = context?.packageManager
                val stockInfo = pm?.getPackageInfo(stockPkg, 0)
                val stockApkPath = stockInfo?.applicationInfo?.sourceDir
                if (stockApkPath != null) {
                    val stockFile = File(stockApkPath)
                    if (stockFile.exists()) {
                        XposedBridge.log("ReVancedXposed: Found stock Spotify at $stockApkPath. Using it for signatures.")
                        return stockFile
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("ReVancedXposed: Could not find stock app: ${e.message}")
            }
        }
        
        // 2. Fallback su percorsi pubblici
        val potentialPaths = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "base.apk"),
            File("/sdcard/Android/media/$stockPkg/base.apk"),
            File("/sdcard/Download/base.apk")
        )

        val publicApk = potentialPaths.find { it.exists() && it.canRead() }

        if (publicApk != null && (!internalApk.exists() || publicApk.lastModified() > internalApk.lastModified())) {
            XposedBridge.log("ReVancedXposed: Found original APK at ${publicApk.absolutePath}. Copying to internal cache...")
            try {
                internalApk.parentFile?.mkdirs()
                publicApk.copyTo(internalApk, overwrite = true)
                internalApk.setReadable(true, false)
                XposedBridge.log("ReVancedXposed: APK copied successfully to ${internalApk.absolutePath}")
            } catch (e: Exception) {
                XposedBridge.log("ReVancedXposed: Failed to copy APK from ${publicApk.absolutePath}: ${e.message}")
            }
        } else if (!internalApk.exists()) {
            XposedBridge.log("ReVancedXposed: Original APK not found or not readable in any public folder.")
            XposedBridge.log("ReVancedXposed: Please ensure base.apk is in Download or Android/media/$stockPkg/")
        }

        return if (internalApk.exists()) internalApk else null
    }

    private fun spoofSignature(lpparam: LoadPackageParam) {
        val originalApkPath = File(lpparam.appInfo.dataDir, "cache/base.apk").absolutePath
        val stockPkg = "com.spotify.music"
        val currentPkg = lpparam.packageName

        // 1. Hook PackageManager (Legacy and Modern)
        val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", lpparam.classLoader)
        
        val pmHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val pkgName = param.args[0] as String
                if (pkgName != currentPkg && pkgName != stockPkg) return

                val info = param.result as? PackageInfo ?: return
                XposedBridge.log("ReVancedXposed: Intercepted getPackageInfo for $pkgName")
                
                applyOriginalSignature(info, originalApkPath, param.thisObject as PackageManager)
            }
        }

        // HookInt flags version
        XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, pmHook)
        
        // Hook PackageInfoFlags version (Android 13+)
        try {
            val flagsClass = XposedHelpers.findClass("android.content.pm.PackageManager\$PackageInfoFlags", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(pmClass, "getPackageInfo", String::class.java, flagsClass, pmHook)
        } catch (ignored: Throwable) {
            // Probably older Android version
        }

        // 2. Deep Hook: IPackageManager (ActivityThread)
        try {
            val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader)
            val sPackageManagerField = XposedHelpers.findField(activityThreadClass, "sPackageManager")
            val originalProxy = sPackageManagerField.get(null)

            if (originalProxy != null) {
                XposedBridge.log("ReVancedXposed: Deep hooking IPackageManager proxy")
                XposedBridge.hookAllMethods(originalProxy.javaClass, "getPackageInfo", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pkgName = param.args[0] as? String ?: return
                        if (pkgName != currentPkg && pkgName != stockPkg) return
                        
                        val info = param.result as? PackageInfo ?: return
                        XposedBridge.log("ReVancedXposed: IPackageManager intercepted $pkgName")
                        
                        // Use system package manager to get signatures from archive
                        val context = XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? android.content.Context
                        val pm = context?.packageManager
                        if (pm != null) {
                            applyOriginalSignature(info, originalApkPath, pm)
                        }
                    }
                })
            }
        } catch (e: Throwable) {
            XposedBridge.log("ReVancedXposed: Failed to deep hook IPackageManager: ${e.message}")
        }
    }

    private fun applyOriginalSignature(info: PackageInfo, originalApkPath: String, pm: PackageManager) {
        try {
            val fmi = pm.getPackageArchiveInfo(originalApkPath, PackageManager.GET_SIGNATURES)
            val originalSignatures = fmi?.signatures
            
            if (!originalSignatures.isNullOrEmpty()) {
                info.signatures = originalSignatures
                // Update SigningInfo for Android 9+
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    try {
                        val signingInfoClass = XposedHelpers.findClass("android.content.pm.SigningInfo", pm.javaClass.classLoader)
                        val signingInfo = XposedHelpers.newInstance(signingInfoClass)
                        // This is more complex on newer versions but setting .signatures often suffices
                        XposedHelpers.setObjectField(info, "signingInfo", signingInfo)
                    } catch (ignored: Exception) {
                        // Ignore signingInfo failures
                    }
                }
                XposedBridge.log("ReVancedXposed: Successfully spoofed signatures from $originalApkPath")
            }
        } catch (e: Exception) {
            XposedBridge.log("ReVancedXposed: Error applying signatures: ${e.message}")
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
                        if (className.contains("xposed") || 
                            className.contains("lsposed") || 
                            className.contains("revanced") ||
                            className.contains("lspatch") ||
                            className.contains("npatch") ||
                            className.contains("chsbuffer")) {
                            modified = true
                            continue
                        }
                        filteredList.add(element)
                    }
                }
                
                if (modified) {
                    param.result = filteredList.toTypedArray()
                }
            }
        }

        try {
            // Hook Throwable.getStackTrace()
            XposedHelpers.findAndHookMethod(
                Throwable::class.java, 
                "getStackTrace", 
                stealthHook
            )

            // Hook Thread.getStackTrace()
            XposedHelpers.findAndHookMethod(
                Thread::class.java, 
                "getStackTrace", 
                stealthHook
            )
        } catch (e: Throwable) {
            XposedBridge.log("ReVancedXposed: Failed to hook stack trace: ${e.message}")
        }
    }

    private fun bypassGmsIntegrity(lpparam: LoadPackageParam) {
        val stockPkg = "com.spotify.music"
        val currentPkg = lpparam.packageName

        // Hook per ingannare i GMS quando interrogano la firma di Spotify
        XposedHelpers.findAndHookMethod(
            "android.app.ApplicationPackageManager",
            lpparam.classLoader,
            "getPackageInfo",
            String::class.java,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pkgName = param.args[0] as? String ?: return
                    // Se l'app che chiede è GMS e il bersaglio è Spotify, o viceversa
                    if (pkgName == currentPkg || pkgName == stockPkg) {
                        val info = param.result as? PackageInfo ?: return
                        val originalApkPath = File(lpparam.appInfo.dataDir, "cache/base.apk").absolutePath
                        applyOriginalSignature(info, originalApkPath, param.thisObject as PackageManager)
                    }
                }
            }
        )
        
        // Hook Play Integrity / SafetyNet results
        try {
            val integrityClass = XposedHelpers.findClass("com.google.android.gms.tasks.Task", lpparam.classLoader)
            XposedBridge.hookAllMethods(integrityClass, "addOnSuccessListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Logga se vediamo attività di Play Integrity
                    XposedBridge.log("ReVancedXposed: GMS Task SuccessListener intercepted")
                }
            })
        } catch (ignored: Throwable) {}
    }

    private fun bypassAndroidIdRestriction(lpparam: LoadPackageParam) {
        // Spoof Settings.Secure.ANDROID_ID to avoid "not approved" errors
        XposedHelpers.findAndHookMethod(
            "android.provider.Settings\$Secure",
            lpparam.classLoader,
            "getString",
            android.content.ContentResolver::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args[1] as? String
                    if (name == Settings.Secure.ANDROID_ID) {
                        // Return a generic/fake Android ID if needed, 
                        // but usually just allowing the call suffices or returning a valid-looking hex
                        param.result = "8888888888888888" 
                        XposedBridge.log("ReVancedXposed: Spoofed ANDROID_ID for Spotify")
                    }
                }
            }
        )
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
