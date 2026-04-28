package io.github.chsbuffer.revancedxposed.spotify.misc.logout

import android.util.Log
import io.github.chsbuffer.revancedxposed.ChimeraBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import java.lang.reflect.Method

private const val TAG = "LogOutPatch"

private object AuthCache {
    @Volatile var body: String? = null
    @Volatile var contentType: Any? = null
}

fun SpotifyHook.LogOutPatch() {
    val cl = classLoader

    fun findMethodSafe(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
        return runCatching { clazz.getDeclaredMethod(name, *params).apply { isAccessible = true } }.getOrNull()
            ?: runCatching { clazz.methods.firstOrNull { it.name == name && it.parameterTypes.size == params.size }?.apply { isAccessible = true } }.getOrNull()
    }

    // --- LAYER 1 & 2: Network Interceptor (OkHttp3) ---
    try {
        val chainClass = runCatching { Class.forName("okhttp3.Interceptor\$Chain", false, cl) }.getOrNull() ?: return
        val reqClass = Class.forName("okhttp3.Request", false, cl)
        val builderClass = Class.forName("okhttp3.Response\$Builder", false, cl)
        val bodyClass = Class.forName("okhttp3.ResponseBody", false, cl)
        val mtClass = Class.forName("okhttp3.MediaType", false, cl)

        val proceedMethod = chainClass.declaredMethods.firstOrNull {
            it.name == "proceed" && it.parameterTypes.size == 1 && it.parameterTypes[0] == reqClass
        } ?: return

        ChimeraBridge.hookMethod(proceedMethod, object : ChimeraBridge.XC_MethodHook() {
            override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                try {
                    val req = param.args?.get(0) ?: return
                    val url = findMethodSafe(req.javaClass, "url")?.invoke(req) ?: return
                    val host = findMethodSafe(url.javaClass, "host")?.invoke(url) as? String ?: ""
                    val path = findMethodSafe(url.javaClass, "encodedPath")?.invoke(url) as? String ?: ""

                    val isDetection = path.contains("dual-sync") ||
                            path.contains("social-connect") ||
                            path.contains("melody/v1/check")

                    if (host.contains("spclient") && isDetection) {
                        Log.i(TAG, "★ L2: Detection Path BLOCKED -> $path")

                        val protocolClass = Class.forName("okhttp3.Protocol", false, cl)
                        val http11 = protocolClass.getField("HTTP_1_1").get(null)
                        val builder = builderClass.getConstructor().newInstance()

                        findMethodSafe(builderClass, "request", reqClass)?.invoke(builder, req)
                        findMethodSafe(builderClass, "protocol", protocolClass)?.invoke(builder, http11)
                        findMethodSafe(builderClass, "code", Int::class.java)?.invoke(builder, 204)
                        findMethodSafe(builderClass, "message", String::class.java)?.invoke(builder, "Blocked")

                        val emptyBody = findMethodSafe(bodyClass, "create", mtClass, String::class.java)?.invoke(null, null, "")
                        findMethodSafe(builderClass, "body", bodyClass)?.invoke(builder, emptyBody)

                        param.setResult(findMethodSafe(builderClass, "build")?.invoke(builder))
                    }
                } catch (_: Exception) { }
            }

            override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                try {
                    val resp = param.result ?: return
                    val code = findMethodSafe(resp.javaClass, "code")?.invoke(resp) as? Int ?: return
                    val req = findMethodSafe(resp.javaClass, "request")?.invoke(resp) ?: return
                    val url = findMethodSafe(req.javaClass, "url")?.invoke(req) ?: return
                    val host = findMethodSafe(url.javaClass, "host")?.invoke(url) as? String ?: ""

                    val isAuthEndpoint = host.contains("login5") || host.contains("googleusercontent") || host.contains("spotify.com")

                    if (code in 200..299 && isAuthEndpoint) {
                        val bodyObj = findMethodSafe(resp.javaClass, "body")?.invoke(resp) ?: return
                        val peeked = runCatching {
                            Long::class.javaPrimitiveType?.let { findMethodSafe(resp.javaClass, "peekBody", it) }
                                ?.invoke(resp, 65536L)
                        }.getOrNull() ?: bodyObj

                        val text = findMethodSafe(peeked.javaClass, "string")?.invoke(peeked) as? String
                        if (text?.contains("access_token") == true) {
                            AuthCache.body = text
                            AuthCache.contentType = findMethodSafe(peeked.javaClass, "contentType")?.invoke(peeked)
                            Log.d(TAG, "★ L1: Auth Token CACHED")
                        }
                    } else if ((code == 401 || code == 403) && AuthCache.body != null && isAuthEndpoint) {
                        Log.w(TAG, "★ L1: Auth REJECTED ($code) -> REPLAYING cached success response")

                        val builder = findMethodSafe(resp.javaClass, "newBuilder")?.invoke(resp) ?: return
                        findMethodSafe(builder.javaClass, "code", Int::class.java)?.invoke(builder, 200)
                        findMethodSafe(builder.javaClass, "message", String::class.java)?.invoke(builder, "OK")

                        val replayBody = findMethodSafe(bodyClass, "create", mtClass, String::class.java)
                            ?.invoke(null, AuthCache.contentType, AuthCache.body)
                        findMethodSafe(builder.javaClass, "body", bodyClass)?.invoke(builder, replayBody)

                        param.setResult(findMethodSafe(builder.javaClass, "build")?.invoke(builder))
                    }
                } catch (_: Exception) { }
            }
        })
    } catch (e: Throwable) {
        Log.e(TAG, "L1/L2 Hook Failure: ${e.message}")
    }

    // --- LAYER 3: SharedPreferences Protection ---
    try {
        val editorClass = android.content.SharedPreferences.Editor::class.java
        val protectedKeys = setOf("token", "session", "auth", "login", "account", "credential")

        ChimeraBridge.hookMethod(
            editorClass.getDeclaredMethod("remove", String::class.java),
            object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    val key = param.args?.get(0) as? String ?: return
                    if (protectedKeys.any { key.lowercase().contains(it) }) {
                        Log.i(TAG, "★ L3: Blocked removal of auth key: $key")
                        param.setResult(param.thisObject)
                    }
                }
            }
        )

        ChimeraBridge.hookMethod(
            editorClass.getDeclaredMethod("clear"),
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
