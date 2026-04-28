package io.github.chsbuffer.revancedxposed

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.revanced.extension.shared.Utils
import io.github.chsbuffer.revancedxposed.spotify.AdBlockHook
import io.github.chsbuffer.revancedxposed.spotify.RoundyUIHook
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import io.github.chsbuffer.revancedxposed.spotify.ThemeHook
import android.os.Environment
import android.provider.Settings
import java.io.File

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
            // La libreria "crashlytics" (ghost) è già caricata via Smali in SpotifyApplication
            log("Native Ghost Shield assumed active (via Smali bootstrap)")
        }

        // --- PHASE 2: LEGACY HOOK PREPARATION ---
        if (context.packageName == "com.spotify.music") {
            // Handle Stock APK Escrow for Dobby
            val internalApk = prepareOriginalApk(context)
            if (internalApk != null) {
                runCatching { setInternalApkPath(internalApk.absolutePath) }
            }
            
            // Advanced Stealth & GMS Bypasses
            hideXposedFromStackTrace()
            bypassAndroidIdRestriction(context)
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
        val internalApk = File(context.filesDir, "stock.apk")
        // Try to find the original APK in several places. 
        // Note: External storage is often restricted (EACCES) on newer Android versions.
        val possibleSources = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "base.apk"),
            File("/sdcard/Android/media/com.spotify.music/base.apk"),
            File(context.getExternalFilesDir(null), "base.apk")
        )

        val publicApk = possibleSources.firstOrNull { it.exists() && it.canRead() }

        if (publicApk != null && (!internalApk.exists() || publicApk.lastModified() > internalApk.lastModified())) {
            log("Syncing stock APK from ${publicApk.absolutePath} to internal cache...")
            try {
                internalApk.parentFile?.mkdirs()
                publicApk.copyTo(internalApk, overwrite = true)
                internalApk.setReadable(true, false)
                log("Sync successful.")
            } catch (e: Exception) {
                log("Sync failed -> ${e.message}")
            }
        } else if (publicApk == null && !internalApk.exists()) {
            log("No original base.apk found! Fingerprints might fail. Place original APK in Spotify's 'files' folder as 'base.apk'")
        }

        return if (internalApk.exists()) internalApk else null
    }

    private fun bypassAndroidIdRestriction(context: Context) {
        try {
            val secureClass = "android.provider.Settings\$Secure".findClass(context.classLoader)
            val getStringMethod = secureClass.getDeclaredMethodRecursive("getString", android.content.ContentResolver::class.java, String::class.java)
            
            // Generiamo un ID basato sul package name per coerenza
            val pseudoId = context.packageName.hashCode().toLong().toString(16).padStart(16, 'a')

            ChimeraBridge.hookMethod(getStringMethod, object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    if (param.args?.get(1) == Settings.Secure.ANDROID_ID) {
                        param.setResult(pseudoId)
                        log("Spoofed ANDROID_ID ($pseudoId) via ChimeraBridge")
                    }
                }
            })

            // AGGIUNTO: Spoof Installer e Signature
            spoofPackageManager(context)
            
        } catch (e: Throwable) {
            log("Failed to bypass identity restrictions: ${e.message}")
        }
    }

    private fun spoofPackageManager(context: Context) {
        runCatching {
            val pm = context.packageManager
            val pmClass = pm.javaClass
            
            // 1. Estraiamo la firma originale dallo stock.apk che abbiamo sincronizzato
            val stockApkFile = File(context.filesDir, "stock.apk")
            if (!stockApkFile.exists()) return@runCatching
            
            val flags = android.content.pm.PackageManager.GET_SIGNATURES
            val archiveInfo = pm.getPackageArchiveInfo(stockApkFile.absolutePath, flags)
            val originalSignatures = archiveInfo?.signatures
            
            if (originalSignatures == null || originalSignatures.isEmpty()) {
                log("Failed to extract original signatures from stock APK")
                return@runCatching
            }

            log("Original signatures extracted. Starting PackageManager spoof...")

            // 2. Hook getPackageInfo per restituire la firma originale
            val getPackageInfoMethod = pmClass.getDeclaredMethodRecursive("getPackageInfo", String::class.java, Int::class.javaPrimitiveType!!)
            
            ChimeraBridge.hookMethod(getPackageInfoMethod, object : ChimeraBridge.XC_MethodHook() {
                override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    val pkgName = param.args?.get(0) as? String ?: return
                    val flagsRequested = param.args?.get(1) as? Int ?: 0
                    
                    if (pkgName == context.packageName && (flagsRequested and android.content.pm.PackageManager.GET_SIGNATURES != 0)) {
                        val info = param.result as? android.content.pm.PackageInfo ?: return
                        info.signatures = originalSignatures
                        log("Spoofed signatures for $pkgName")
                    }
                }
            })

            // 3. Spoof Installer (Fai credere che venga dal Play Store)
            val getInstallerMethod = try {
                pmClass.getDeclaredMethodRecursive("getInstallerPackageName", String::class.java)
            } catch (e: NoSuchMethodException) { null }

            if (getInstallerMethod != null) {
                ChimeraBridge.hookMethod(getInstallerMethod, object : ChimeraBridge.XC_MethodHook() {
                    override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                        val pkgName = param.args?.get(0) as? String
                        if (pkgName == context.packageName) {
                            param.setResult("com.android.vending")
                        }
                    }
                })
            }
        }.onFailure { log("PackageManager spoof failed: ${it.message}") }
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
        val instance = MainHook()

        fun log(message: String) {
            android.util.Log.i("Chimera:Main", message)
        }

        fun log(e: Throwable) {
            android.util.Log.e("Chimera:Main", "Error", e)
        }
    }
}

fun inContext(context: Application, f: (Application) -> Unit) {
    try {
        ChimeraBridge.hookMethod(
            context.javaClass.getDeclaredMethodRecursive("onCreate"),
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
