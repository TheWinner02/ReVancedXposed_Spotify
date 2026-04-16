package io.github.chsbuffer.revancedxposed.spotify.misc.login

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook

@Suppress("unused")
fun SpotifyHook.FixFacebookLoginPatch() { // Minuscola per convenzione Kotlin
    val TAG = "FixFacebookLogin"

    runCatching {
        val cl = classLoader
        XposedBridge.log("$TAG -> init")

        val clazz = runCatching {
            cl.loadClass("com.facebook.login.KatanaProxyLoginMethodHandler")
        }.getOrNull() ?: return // Esci se non trovi la classe

        // Cerchiamo il metodo che:
        // 1. Restituisce un Int
        // 2. HA ALMENO UN PARAMETRO (la richiesta di login)
        val method = clazz.declaredMethods.firstOrNull { m ->
            m.returnType == Int::class.javaPrimitiveType && m.parameterTypes.isNotEmpty()
        }

        if (method == null) {
            XposedBridge.log("$TAG -> tryAuthorize non trovato (metodo con parametri + Int)")
            return
        }

        XposedBridge.log("$TAG -> Hooking ${clazz.name}.${method.name}")

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Impostiamo il risultato a 0 (equivale a returnEarly(0))
                // Questo dice all'SDK che l'app Facebook non può gestire la richiesta
                param.result = 0
            }
        })

    }.onFailure {
        XposedBridge.log("$TAG error -> ${it.message}")
    }
}