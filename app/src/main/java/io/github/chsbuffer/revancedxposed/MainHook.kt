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
    private external fun setMapsCachePath(path: String)

    fun handleStandalone(context: Application) {
        if (isInitialized) return
        isInitialized = true

        log("Entering standalone process ${context.packageName}")
        this.app = context

        // --- PHASE 1: NATIVE GHOST SHIELD ---
        if (context.packageName == "com.spotify.music") {
            // La libreria "crashlytics" (ghost) è già caricata via Smali in SpotifyApplication
            log("Native Ghost Shield assumed active (via Smali bootstrap)")
            runCatching { setMapsCachePath(context.cacheDir.absolutePath) }
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

            // AGGIUNTO: Deep Security Bypass
            bypassDebugFlags(context)
            spoofSystemProperties()
            spoofPackageManager(context)
            
        } catch (e: Throwable) {
            log("Failed to bypass identity restrictions: ${e.message}")
        }
    }

    private fun bypassDebugFlags(context: Context) {
        runCatching {
            val appInfo = context.applicationInfo
            // Rimuoviamo il flag debuggable che MT Manager o il sistema potrebbero aver settato
            appInfo.flags = appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
            
            // Hookiamo Debug.isDebuggerConnected()
            val debugClass = Class.forName("android.os.Debug")
            val isDebuggerConnectedMethod = debugClass.getDeclaredMethod("isDebuggerConnected")
            ChimeraBridge.hookMethod(isDebuggerConnectedMethod, object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    param.setResult(false)
                }
            })
            log("Debug flags and debugger detection neutralized.")
        }
    }

    private fun spoofSystemProperties() {
        runCatching {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getDeclaredMethodRecursive("get", String::class.java)
            
            ChimeraBridge.hookMethod(getMethod, object : ChimeraBridge.XC_MethodHook() {
                override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    val key = param.args?.get(0) as? String ?: return
                    val fakeValue = when(key) {
                        "ro.debuggable" -> "0"
                        "ro.secure" -> "1"
                        "ro.build.selinux" -> "1"
                        "ro.build.tags" -> "release-keys"
                        "ro.product.model" -> "SM-S911B" // Galaxy S23
                        "ro.product.brand" -> "samsung"
                        else -> return
                    }
                    param.setResult(fakeValue)
                    log("Spoofed SystemProperty $key -> $fakeValue")
                }
            })
        }
    }

    @SuppressLint("PrivateApi")
    private fun spoofPackageManager(context: Context) {
        runCatching {
            val pm = context.packageManager
            // Hookiamo direttamente la classe di implementazione del PackageManager
            val pmClass = Class.forName("android.app.ApplicationPackageManager")
            
            val stockApkFile = File(context.filesDir, "stock.apk")
            if (!stockApkFile.exists()) return@runCatching
            
            // Estraiamo le firme originali (vecchio e nuovo metodo)
            val flags = android.content.pm.PackageManager.GET_SIGNATURES or
                        (if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else 0)
            
            val archiveInfo = pm.getPackageArchiveInfo(stockApkFile.absolutePath, flags)
            val originalSignatures = archiveInfo?.signatures
            val originalSigningInfo = if (android.os.Build.VERSION.SDK_INT >= 28) archiveInfo?.signingInfo else null
            
            if (originalSignatures == null && originalSigningInfo == null) {
                log("Failed to extract original signatures/signingInfo")
                return@runCatching
            }

            log("Original signatures extracted. Applying deep PackageManager spoof...")

            val getPackageInfoMethod = pmClass.getDeclaredMethodRecursive("getPackageInfo", String::class.java, Int::class.javaPrimitiveType!!)
            
            ChimeraBridge.hookMethod(getPackageInfoMethod, object : ChimeraBridge.XC_MethodHook() {
                override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    val pkgName = param.args?.get(0) as? String ?: return
                    if (pkgName == context.packageName) {
                        val info = param.result as? android.content.pm.PackageInfo ?: return
                        
                        // Spoof Firme (Legacy)
                        if (originalSignatures != null) {
                            info.signatures = originalSignatures
                        }
                        
                        // Spoof SigningInfo (Modern API 28+)
                        if (android.os.Build.VERSION.SDK_INT >= 28 && originalSigningInfo != null) {
                            info.signingInfo = originalSigningInfo
                        }
                        
                        log("Deep Spoofed signatures/signingInfo for $pkgName")
                    }
                }
            })

            // Spoof Installer (Fai credere che venga dal Play Store)
            val getInstallerMethod = try {
                pmClass.getDeclaredMethodRecursive("getInstallerPackageName", String::class.java)
            } catch (e: Exception) { null }

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
        }.onFailure { log("Deep PackageManager spoof failed: ${it.message}") }
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
                    val terms = listOf("xposed", "revanced", "chsbuffer", "chimera", "ghost", "dexkit", "pine")
                    if (terms.any { result.lowercase().contains(it) }) {
                        param.setResult(result.replace(Regex("(?i)(xposed|revanced|chsbuffer|chimera|ghost|dexkit|pine)"), "AndroidRuntime"))
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
