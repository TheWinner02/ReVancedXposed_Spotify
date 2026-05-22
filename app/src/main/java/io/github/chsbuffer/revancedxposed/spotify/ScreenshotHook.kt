package io.github.chsbuffer.revancedxposed.spotify

import android.app.Application
import android.view.SurfaceView
import android.view.Window
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class ScreenshotHook(private val app: Application, private val lpparam: XC_LoadPackage.LoadPackageParam? = null) {
    fun hook() {
        runCatching {
            // Hook Window.setFlags to remove FLAG_SECURE
            XposedHelpers.findAndHookMethod(
                Window::class.java,
                "setFlags",
                Int::class.java,
                Int::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val flags = param.args?.get(0) as Int
                        val mask = param.args?.get(1) as Int
                        if ((mask and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                            param.args!![0] = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                        }
                    }
                }
            )

            // Hook Window.addFlags to remove FLAG_SECURE
            XposedHelpers.findAndHookMethod(
                Window::class.java,
                "addFlags",
                Int::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val flags = param.args?.get(0) as Int
                        if ((flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                            param.args!![0] = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                        }
                    }
                }
            )

            // Hook SurfaceView.setSecure to always set it to false
            XposedHelpers.findAndHookMethod(
                SurfaceView::class.java,
                "setSecure",
                Boolean::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args!![0] = false
                    }
                }
            )

            XposedBridge.log("ScreenshotHook applied successfully")
        }.onFailure {
            XposedBridge.log("ScreenshotHook failed: ${it.message}")
        }
    }
}