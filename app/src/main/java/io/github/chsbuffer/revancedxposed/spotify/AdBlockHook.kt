package io.github.chsbuffer.revancedxposed.spotify

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class AdBlockHook(private val lpparam: LoadPackageParam) {

    fun hook() {
        val cl = lpparam.classLoader
        XposedBridge.log("RE-VANCED XPOSED: Avvio AdBlocker")

        // ==========================================
        // 1. PATCH DEI FLAGS
        // ==========================================
        // Intercetta la classe LoadedFlags e forza il flag "ads" a false.
        try {
            val flagsClass = cl.loadClass("com.spotify.connectivity.flags.LoadedFlags")
            XposedBridge.hookAllMethods(flagsClass, "get", object : XC_MethodHook() {

                // Hook prima che il metodo venga eseguito
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = XposedHelpers.getObjectField(param.args[0], "identifier") as String
                    if (key == "ads") {
                        param.result = false
                    }
                }

                // Hook dopo che il metodo è stato eseguito (doppia sicurezza)
                override fun afterHookedMethod(param: MethodHookParam) {
                    val key = XposedHelpers.getObjectField(param.args[0], "identifier") as String
                    if (key == "ads") {
                        param.result = false
                    }
                }
            })
            XposedBridge.log("AdBlocker: Flag 'ads' forzato a false")
        } catch (e: Throwable) {
            XposedBridge.log("AdBlocker: Errore in LoadedFlags -> ${e.message}")
        }
    }
}