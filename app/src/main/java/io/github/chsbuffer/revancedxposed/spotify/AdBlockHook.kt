package io.github.chsbuffer.revancedxposed.spotify

import io.github.chsbuffer.revancedxposed.ChimeraBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

class AdBlockHook(private val classLoader: ClassLoader) {

    fun hook() {
        val cl = classLoader
        ChimeraBridge.log("RE-VANCED XPOSED: Avvio AdBlocker (Standalone/Pine)")

        runCatching {
            val flagsClass = cl.loadClass("com.spotify.connectivity.flags.LoadedFlags")
            val flagClass = cl.loadClass("com.spotify.connectivity.flags.Flag")
            val getMethod = flagsClass.getDeclaredMethod("get", flagClass)
            
            ChimeraBridge.hookMethod(getMethod, object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    val flag = param.args?.get(0) ?: return
                    val key = runCatching { XposedHelpers.getObjectField(flag, "identifier") as? String }.getOrNull()
                    if (key == "ads") {
                        param.setResult(false)
                    }
                }
            })
            ChimeraBridge.log("AdBlocker: Hook impostato su LoadedFlags")
        }

        runCatching {
            val adsClass = cl.loadClass("com.spotify.adsinternal.adscore.AdsSettings")
            val isAdsEnabledMethod = adsClass.getDeclaredMethod("isAdsEnabled")
            
            ChimeraBridge.hookMethod(isAdsEnabledMethod, object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    param.setResult(false)
                }
            })
            ChimeraBridge.log("AdBlocker: Hook impostato su AdsSettings")
        }

        runCatching {
            val countdownView = cl.loadClass("com.spotify.adsinternal.playback.video.CountdownBarView")
            val onMeasureMethod = countdownView.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            
            ChimeraBridge.hookMethod(onMeasureMethod, object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    XposedHelpers.callMethod(param.thisObject, "setMeasuredDimension", 0, 0)
                    param.setResult(null)
                }
            })
            ChimeraBridge.log("AdBlocker: Hook impostato su CountdownBarView (Hiding)")
        }
    }
}
