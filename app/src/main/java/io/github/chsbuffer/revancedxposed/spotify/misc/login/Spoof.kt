package io.github.chsbuffer.revancedxposed.spotify.misc.login

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

object Spoof {

    fun apply(classLoader: ClassLoader) {

        // 1. SPOOF USER-AGENT (Copiato dalla patch: Spotify/9.0.58 iOS/17.7.2)
        runCatching {
            val userAgentClass = XposedHelpers.findClass("com.spotify.cosmos.shared.CosmosUserAgent", classLoader)
            XposedHelpers.findAndHookMethod(userAgentClass, "get", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"
                }
            })
        }

        // 2. SPOOF CLIENT ID (Copiato dalla patch: il ClientID ufficiale iOS)
        runCatching {
            val authConfigClass = XposedHelpers.findClass("com.spotify.auth.AuthConfig", classLoader)
            XposedHelpers.findAndHookMethod(authConfigClass, "getClientId", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = "58bd3c95768941ea9eb4350aaa033eb3"
                }
            })
        }

        // 3. BYPASS INTEGRITY (Copiato dalla patch: returnEarly false)
        runCatching {
            // Cerchiamo la classe di verifica integrità (il nome cambia, ma cerchiamo il metodo)
            val integrityClass = XposedHelpers.findClass("com.spotify.preload.IntegrityVerification", classLoader)
            XposedHelpers.findAndHookMethod(integrityClass, "runVerification", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = false // Blocca la verifica sul nascere
                }
            })
        }

        // 4. SPOOF VERSION PROPERTIES (iOS Identity)
        runCatching {
            val propertiesClass = XposedHelpers.findClass("com.spotify.base.java.properties.InternalProperties", classLoader)
            XposedHelpers.findAndHookMethod(propertiesClass, "getProperty", String::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    when (param.args[0] as String) {
                        "client_version" -> param.result = "iphone-9.0.58.558.g200011c"
                        "os_version" -> param.result = "17.7.2"
                        "hardware_machine" -> param.result = "iPhone16,1"
                        "platform" -> param.result = "ios"
                    }
                }
            })
        }
    }
}