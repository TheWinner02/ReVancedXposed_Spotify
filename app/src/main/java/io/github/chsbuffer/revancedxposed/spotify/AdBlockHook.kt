package io.github.chsbuffer.revancedxposed.spotify

import io.github.chsbuffer.revancedxposed.*

class AdBlockHook(private val classLoader: ClassLoader) {

    fun hook() {
        val cl = classLoader
        ChimeraBridge.log("Runtime: StreamProcessor starting")

        runCatching {
            val flagsClass = cl.loadClass("com.spotify.connectivity.flags.LoadedFlags")
            val flagClass = cl.loadClass("com.spotify.connectivity.flags.Flag")
            val getMethod = flagsClass.getDeclaredMethod("get", flagClass)

            ChimeraBridge.hookMethod(getMethod, object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    val flag = param.args?.get(0) ?: return
                    val key = runCatching { flag.getObjectField("identifier") as? String }.getOrNull()
                    if (key == "ads") {
                        param.setResult(false)
                    }
                }
            })
            ChimeraBridge.log("StreamProcessor: Logic applied to LoadedFlags")
        }

        runCatching {
            val adsClass = cl.loadClass("com.spotify.adsinternal.adscore.AdsSettings")
            val isAdsEnabledMethod = adsClass.getDeclaredMethodRecursive("isAdsEnabled")

            ChimeraBridge.hookMethod(isAdsEnabledMethod, object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    param.setResult(false)
                }
            })
            ChimeraBridge.log("StreamProcessor: Logic applied to AdsSettings")
        }

        runCatching {
            val countdownView = cl.loadClass("com.spotify.adsinternal.playback.video.CountdownBarView")
            val onMeasureMethod = countdownView.getDeclaredMethodRecursive(
                "onMeasure",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            )

            ChimeraBridge.hookMethod(onMeasureMethod, object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    param.thisObject?.callMethod(
                        "setMeasuredDimension",
                        arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
                        0, 0
                    )
                    param.setResult(null)
                }
            })
            ChimeraBridge.log("StreamProcessor: UI adjustment applied")
        }
    }

}
