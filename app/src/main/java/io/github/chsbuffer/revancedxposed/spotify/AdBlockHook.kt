package io.github.chsbuffer.revancedxposed.spotify

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class AdBlockHook(private val lpparam: LoadPackageParam) {

    fun hook() {
        val cl = lpparam.classLoader
        XposedBridge.log("RE-VANCED XPOSED: Avvio AdBlocker (Refined)")

        // ==========================================
        // 1. PATCH DEI FLAGS (Legacy/Compat)
        // ==========================================
        // Nota: Nelle versioni recenti 'LoadedFlags' è spesso offuscato.
        // La logica principale è ora in ProductStateProto (UnlockPremiumPatch).
        // Questo hook serve come sicurezza aggiuntiva per varianti specifiche.
        runCatching {
            val flagsClass = cl.loadClass("com.spotify.connectivity.flags.LoadedFlags")
            XposedBridge.hookAllMethods(flagsClass, "get", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = runCatching { XposedHelpers.getObjectField(param.args[0], "identifier") as? String }.getOrNull()
                    if (key == "ads") {
                        param.result = false
                    }
                }
            })
            XposedBridge.log("AdBlocker: Hook impostato su LoadedFlags")
        }

        // ==========================================
        // 2. DISATTIVAZIONE IMPOSTAZIONI PUBBLICITÀ
        // ==========================================
        runCatching {
            // Tentiamo di trovare classi note che gestiscono lo stato delle Ads
            val adsClass = cl.loadClass("com.spotify.adsinternal.adscore.AdsSettings")
            XposedBridge.hookAllMethods(adsClass, "isAdsEnabled", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = false
                }
            })
            XposedBridge.log("AdBlocker: Hook impostato su AdsSettings")
        }

        // ==========================================
        // 3. HIDE AD UI COMPONENTS
        // ==========================================
        runCatching {
            val countdownView = cl.loadClass("com.spotify.adsinternal.playback.video.CountdownBarView")
            XposedHelpers.findAndHookMethod(countdownView, "onMeasure", Int::class.java, Int::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    XposedHelpers.callMethod(param.thisObject, "setMeasuredDimension", 0, 0)
                    param.result = null
                }
            })
            XposedBridge.log("AdBlocker: Hook impostato su CountdownBarView (Hiding)")
        }
    }
}