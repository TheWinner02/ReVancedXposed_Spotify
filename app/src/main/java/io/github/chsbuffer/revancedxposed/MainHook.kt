package io.github.chsbuffer.revancedxposed

import android.app.Activity
import android.app.Application
import android.view.ViewGroup
import android.view.HapticFeedbackConstants
import android.view.View
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
import java.util.WeakHashMap
import android.view.KeyEvent
// ...existing imports...

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    lateinit var startupParam: StartupParam
    lateinit var lpparam: LoadPackageParam
    lateinit var app: Application
    var targetPackageName: String? = null
    val hooksByPackage = mapOf(
        "com.spotify.music" to { SpotifyHook(app, lpparam) },
    )

    // Keep track of the anchor view (home tab) so we can remove the listener when the app pauses
    private val anchorViews = WeakHashMap<Activity, View>()
    // Track last volume key press times to detect simultaneous press
    private val lastVolUp = WeakHashMap<Activity, Long>()
    private val lastVolDown = WeakHashMap<Activity, Long>()
    // Cooldown to avoid retriggering repeatedly
    private val lastTrigger = WeakHashMap<Activity, Long>()

    fun shouldHook(packageName: String): Boolean {
        if (!hooksByPackage.containsKey(packageName)) return false
        if (targetPackageName == null) targetPackageName = packageName
        return targetPackageName == packageName
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (!lpparam.isFirstApplication) return
        if (!shouldHook(lpparam.packageName)) return
        this.lpparam = lpparam

        // --- SHAKE TRIGGER: Register listener onResume ---
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (!activity.javaClass.name.contains("MainActivity")) return

                    // Find the Spotify bottom "home" tab (or best-effort candidate) and attach a long-press listener
                    val decorView = activity.window.decorView
                    val candidate = findHomeTab(decorView)
                    if (candidate != null) {
                        // Avoid re-attaching if already attached
                        val existing = anchorViews[activity]
                        if (existing !== candidate) {
                            // Remove listener from previous if any
                            existing?.setOnLongClickListener(null)
                            candidate.setOnLongClickListener { v ->
                                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                SettingsSheet.show(activity, v)
                                true
                            }
                            anchorViews[activity] = candidate
                        }
                    } else {
                        // Fallback: attach to decorView center so user can long-press anywhere
                        val existing = anchorViews[activity]
                        if (existing != decorView) {
                            existing?.setOnLongClickListener(null)
                            decorView.setOnLongClickListener { v ->
                                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                SettingsSheet.show(activity, v)
                                true
                            }
                            anchorViews[activity] = decorView
                        }
                    }
                }
            }
        )

        // --- SHAKE TRIGGER: Unregister listener onPause ---
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "onPause",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (!activity.javaClass.name.contains("MainActivity")) return

                    val anchor = anchorViews[activity]
                    if (anchor != null) {
                        anchor.setOnLongClickListener(null)
                        anchorViews.remove(activity)
                    }
                }
            }
        )

        // --- VOLUME KEYS: Detect simultaneous Volume Up + Volume Down press ---
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            lpparam.classLoader,
            "dispatchKeyEvent",
            KeyEvent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (!activity.javaClass.name.contains("MainActivity")) return

                    val ev = param.args[0] as KeyEvent
                    if (ev.action != KeyEvent.ACTION_DOWN) return

                    val now = System.currentTimeMillis()
                    val threshold = 400L // ms between presses to consider simultaneous

                    when (ev.keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> {
                            lastVolUp[activity] = now
                            val d = lastVolDown[activity] ?: 0L
                            val last = lastTrigger[activity] ?: 0L
                            if (now - d <= threshold && now - last > 1000L) {
                                triggerSettings(activity)
                                lastTrigger[activity] = now
                                param.setResult(true)
                            }
                        }
                        KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            lastVolDown[activity] = now
                            val u = lastVolUp[activity] ?: 0L
                            val last = lastTrigger[activity] ?: 0L
                            if (now - u <= threshold && now - last > 1000L) {
                                triggerSettings(activity)
                                lastTrigger[activity] = now
                                param.setResult(true)
                            }
                        }
                        else -> return
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
            try {
                if (prefs.getBoolean("enable_premium", true)) {
                    hooksByPackage[lpparam.packageName]?.invoke()?.Hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Premium fallita: ${e.message}")
            }

            // --- BLOCCO: AD BLOCK ---
            try {
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

            // --- BLOCCO ROUNDY ---
            try {
                if (prefs.getBoolean("enable_round_ui", true)) {
                    RoundyUIHook(lpparam).hook()
                }
            } catch (e: Exception) {
                XposedBridge.log("Mod Roundy fallita: ${e.message}")
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
    }

    // ...existing code...

    // Walk view hierarchy to find a likely "home" bottom tab candidate
    private fun findHomeTab(root: View): View? {
        try {
            val resName = if (root.id != View.NO_ID) try { root.resources.getResourceEntryName(root.id) } catch (_: Exception) { "" } else ""
            val className = root.javaClass.name.lowercase()

            // Heuristics: resource name containing these substrings or class name hints
            val matchesName = listOf("home", "browse", "evopage", "nav", "navigation", "bottom", "tab").any { resName.contains(it) }
            val matchesClass = listOf("navigation", "bottom", "tab", "evopage").any { className.contains(it) }

            if (matchesName || matchesClass) return root
        } catch (_: Exception) {}

        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                val found = findHomeTab(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun triggerSettings(activity: Activity) {
        try {
            val anchor = anchorViews[activity] ?: activity.window.decorView
            anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            SettingsSheet.show(activity, anchor)
        } catch (_: Exception) {}
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