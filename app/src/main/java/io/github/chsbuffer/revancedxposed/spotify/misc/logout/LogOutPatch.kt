package io.github.chsbuffer.revancedxposed.spotify.misc.logout

import android.util.Log
import io.github.chsbuffer.revancedxposed.*
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import java.lang.reflect.Method

private const val TAG = "LogOutPatch"

private object AuthCache {
    @Volatile var body: String? = null
    @Volatile var contentType: Any? = null
}

fun SpotifyHook.LogOutPatch() {
    val cl = classLoader

    // --- LAYER 1 & 2: Network Interceptor (OkHttp3) ---
    try {
        val chainClass = "okhttp3.Interceptor\$Chain".findClassOrNull(cl) ?: return
        val reqClass = "okhttp3.Request".findClass(cl)
        val builderClass = "okhttp3.Response\$Builder".findClass(cl)
        val bodyClass = "okhttp3.ResponseBody".findClass(cl)
        val mtClass = "okhttp3.MediaType".findClass(cl)

        val proceedMethod = chainClass.getDeclaredMethodRecursive("proceed", reqClass)

        ChimeraBridge.hookMethod(proceedMethod, object : ChimeraBridge.XC_MethodHook() {
            override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                try {
                    val req = param.args?.get(0) ?: return
                    val url = req.callMethod("url") ?: return
                    val host = url.callMethod("host") as? String ?: ""
                    val path = url.callMethod("encodedPath") as? String ?: ""

                    val isDetection = path.contains("dual-sync") ||
                            path.contains("social-connect") ||
                            path.contains("melody/v1/check")

                    if (host.contains("spclient") && isDetection) {
                        Log.i(TAG, "★ L2: Detection Path BLOCKED -> $path")

                        val protocolClass = "okhttp3.Protocol".findClass(cl)
                        val http11 = protocolClass.getDeclaredFieldRecursive("HTTP_1_1").get(null)
                        val builder = builderClass.getDeclaredConstructor().newInstance()

                        builder.callMethod("request", reqClass, req)
                        builder.callMethod("protocol", protocolClass, http11)
                        builder.callMethod("code", Int::class.javaPrimitiveType!!, 204)
                        builder.callMethod("message", String::class.java, "Blocked")

                        val emptyBody = bodyClass.callStaticMethod("create", arrayOf(mtClass, String::class.java), null, "")
                        builder.callMethod("body", bodyClass, emptyBody)

                        param.setResult(builder.callMethod("build"))
                    }
                } catch (e: Exception) { 
                    ChimeraBridge.log("L1/L2 Before Error: ${e.message}")
                }
            }

            override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                try {
                    val resp = param.result ?: return
                    val code = resp.callMethod("code") as? Int ?: return
                    val req = resp.callMethod("request") ?: return
                    val url = req.callMethod("url") ?: return
                    val host = url.callMethod("host") as? String ?: ""

                    val isAuthEndpoint = host.contains("login5") || host.contains("googleusercontent") || host.contains("spotify.com")

                    if (code in 200..299 && isAuthEndpoint) {
                        val bodyObj = resp.callMethod("body") ?: return
                        val peeked = runCatching {
                            resp.callMethod("peekBody", 65536L)
                        }.getOrNull() ?: bodyObj

                        val text = peeked.callMethod("string") as? String
                        if (text?.contains("access_token") == true) {
                            AuthCache.body = text
                            AuthCache.contentType = peeked.callMethod("contentType")
                            Log.d(TAG, "★ L1: Auth Token CACHED")
                        }
                    } else if ((code == 401 || code == 403) && AuthCache.body != null && isAuthEndpoint) {
                        Log.w(TAG, "★ L1: Auth REJECTED ($code) -> REPLAYING cached success response")

                        val builder = resp.callMethod("newBuilder") ?: return
                        builder.callMethod("code", Int::class.javaPrimitiveType!!, 200)
                        builder.callMethod("message", String::class.java, "OK")

                        val replayBody = bodyClass.callStaticMethod("create", arrayOf(mtClass, String::class.java), AuthCache.contentType, AuthCache.body)
                        builder.callMethod("body", bodyClass, replayBody)

                        param.setResult(builder.callMethod("build"))
                    }
                } catch (e: Exception) {
                    ChimeraBridge.log("L1/L2 After Error: ${e.message}")
                }
            }
        })
    } catch (e: Throwable) {
        Log.e(TAG, "L1/L2 Hook Failure: ${e.message}")
    }

    // --- LAYER 3: SharedPreferences Protection ---
    try {
        val editorClass = android.content.SharedPreferences.Editor::class.java

        ChimeraBridge.hookMethod(
            editorClass.getDeclaredMethodRecursive("remove", String::class.java),
            object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    val key = param.args?.get(0) as? String ?: return
                    val protectedKeys = setOf("token", "session", "auth", "login", "account", "credential")
                    if (protectedKeys.any { key.lowercase().contains(it) }) {
                        Log.i(TAG, "★ L3: Blocked removal of auth key: $key")
                        param.setResult(param.thisObject)
                    }
                }
            }
        )

        ChimeraBridge.hookMethod(
            editorClass.getDeclaredMethodRecursive("clear"),
            object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    Log.w(TAG, "★ L3: Blocked SharedPreferences.clear() to preserve session")
                    param.setResult(param.thisObject)
                }
            }
        )
    } catch (e: Throwable) {
        Log.e(TAG, "L3 Hook Failure: ${e.message}")
    }
}
