package io.github.chsbuffer.revancedxposed

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
import io.github.chsbuffer.revancedxposed.spotify.misc.login.Spoof
import io.github.chsbuffer.revancedxposed.spotify.misc.login.setupIntegratedLogin
import io.github.chsbuffer.revancedxposed.spotify.misc.login.StealthMode
import kotlin.concurrent.thread
import org.luckypray.dexkit.DexKitBridge

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    lateinit var startupParam: StartupParam
    lateinit var lpparam: LoadPackageParam
    lateinit var app: Application
    var targetPackageName: String? = null
    val hooksByPackage = mapOf(
        "com.spotify.music" to { SpotifyHook(app, lpparam) },
    )

    fun shouldHook(packageName: String): Boolean {
        if (!hooksByPackage.containsKey(packageName)) return false
        if (targetPackageName == null) targetPackageName = packageName
        return targetPackageName == packageName
    }
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        Spoof.apply(lpparam.classLoader, lpparam.appInfo.sourceDir)
        StealthMode(lpparam.classLoader)
        NetworkTracer(lpparam.classLoader, lpparam.appInfo.sourceDir)
        if (!lpparam.isFirstApplication) return
        if (!shouldHook(lpparam.packageName)) return
        this.lpparam = lpparam

        // --- NUOVO TRIGGER: LONG CLICK SU ICONA PROFILO ---
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onPostCreate", // Usiamo onPostCreate per essere sicuri che la UI sia pronta
            android.os.Bundle::class.java,
            object : XC_MethodHook() {
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
            setupIntegratedLogin(lpparam.classLoader)

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

    private fun NetworkTracer(classLoader: ClassLoader, apkPath: String) {
        val TAG = "NETWORK-TRACE"

        thread {
            try {
                System.loadLibrary("dexkit")
                DexKitBridge.create(apkPath).use { bridge ->
                    // Cerchiamo il metodo 'header(String, String)' di OkHttp
                    // cercando chi usa la stringa "User-Agent"
                    val result = bridge.findMethod {
                        matcher {
                            // Cerchiamo i metodi che si chiamano "header"
                            name = "header"
                            // Che accettano due stringhe come parametri
                            paramTypes("java.lang.String", "java.lang.String")
                        }
                    }.singleOrNull()

                    result?.let { methodData ->
                        val method = methodData.getMethodInstance(classLoader)
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val name = param.args[0] as String
                                val value = param.args[1] as String

                                if (name.equals("User-Agent", ignoreCase = true)) {
                                    XposedBridge.log("$TAG: Rilevato tentativo di impostare UA -> $value")
                                    // Qui possiamo forzare iOS se vediamo che è Android
                                    if (value.contains("Android")) {
                                        param.args[1] = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"
                                        XposedBridge.log("$TAG: UA Android forzato a iOS!")
                                    }
                                }
                            }
                        })
                        XposedBridge.log("$TAG: Hook dinamico su OkHttp (offuscato) completato.")
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("$TAG: Errore DexKit: ${e.message}")
            }
        }
    }

    // Funzione per impostare il listener e dare feedback
    private fun setModLongClickListener(view: View, activity: Activity) {
        if (view.tag == "mod_hooked") return
        view.tag = "mod_hooked"

        view.setOnLongClickListener {
            // Se la view cliccata è un contenitore (ViewGroup), cerchiamo l'immagine dentro
            val realView = if (it is android.view.ViewGroup && it.childCount > 0) {
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
