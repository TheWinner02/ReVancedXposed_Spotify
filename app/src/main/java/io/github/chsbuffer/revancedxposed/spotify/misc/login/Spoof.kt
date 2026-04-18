package io.github.chsbuffer.revancedxposed.spotify.misc.login

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

object Spoof {

    fun apply(classLoader: ClassLoader) {

        // 1. KILL INTEGRITY VERIFICATION (Il "colpevole" del log)
        // Questo hook blocca il metodo che scansiona l'app in cerca di oPatch/Xposed
        runCatching {
            val integrityClass = XposedHelpers.findClass("com.spotify.puffin.core.integration.NativeIntegrityVerification", classLoader)
            XposedHelpers.findAndHookMethod(integrityClass, "isIntegrated", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true // Diciamo all'app che tutto è a posto
                }
            })
        }

        // 2. SPOOF IOS IDENTITY (Come visto nella patch ReVanced)
        // Spotify si fida di più dei client iPhone, riducendo i controlli ReCaptcha
        runCatching {
            val propertiesClass = XposedHelpers.findClass("com.spotify.base.java.properties.InternalProperties", classLoader)
            XposedHelpers.findAndHookMethod(propertiesClass, "getProperty", String::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    when (param.args[0] as String) {
                        "client_version" -> param.result = "iphone-9.0.58.558.g200011c"
                        "platform" -> param.result = "ios"
                        "os_version" -> param.result = "17.7.2"
                    }
                }
            })
        }

        // 3. BYPASS SSL PINNING (Per correggere l'errore Conscrypt nel log)
        runCatching {
            val trustManagerClass = XposedHelpers.findClass("com.android.org.conscrypt.TrustManagerImpl", classLoader)
            XposedHelpers.findAndHookMethod(trustManagerClass, "checkTrustedRecursive",
                Array<java.security.cert.X509Certificate>::class.java, String::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, List::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = emptyList<java.security.cert.X509Certificate>()
                    }
                })
        }
    }
}