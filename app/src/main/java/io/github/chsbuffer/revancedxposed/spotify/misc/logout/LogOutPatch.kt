package io.github.chsbuffer.revancedxposed.spotify.misc.logout

import app.revanced.extension.shared.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import java.lang.reflect.Method

private object AuthCache {
    @Volatile var body: String? = null
    @Volatile var contentType: Any? = null
}

fun SpotifyHook.LogOutPatch() {
    val cl = classLoader

    // --- HELPER PER REFLECTION RESILIENTE ---
    fun findMethodSafe(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
        return runCatching { clazz.getDeclaredMethod(name, *params).apply { isAccessible = true } }.getOrNull()
            ?: runCatching {
                clazz.methods.firstOrNull { it.name == name && it.parameterTypes.size == params.size }?.apply { isAccessible = true }
            }.getOrNull()
    }

    // --- LAYER 1 & 2: Network Interceptor (OkHttp3) ---
    runCatching {
        val chainClass = Class.forName("okhttp3.Interceptor\$Chain", false, cl)
        val reqClass = Class.forName("okhttp3.Request", false, cl)
        val respClass = Class.forName("okhttp3.Response", false, cl)
        val builderClass = Class.forName("okhttp3.Response\$Builder", false, cl)
        val bodyClass = Class.forName("okhttp3.ResponseBody", false, cl)
        val mtClass = Class.forName("okhttp3.MediaType", false, cl)

        val proceedMethod = chainClass.declaredMethods.firstOrNull {
            it.name == "proceed" && it.parameterTypes.size == 1 && it.parameterTypes[0] == reqClass
        } ?: return@runCatching

        XposedBridge.hookMethod(proceedMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching {
                    val req = param.args[0] ?: return@runCatching
                    val url = findMethodSafe(req.javaClass, "url")?.invoke(req) ?: return@runCatching
                    val host = findMethodSafe(url.javaClass, "host")?.invoke(url) as? String ?: ""
                    val path = findMethodSafe(url.javaClass, "encodedPath")?.invoke(url) as? String ?: ""

                    // LAYER 2: Blocco percorsi di detection (spclient)
                    val isDetection = path.contains("dual-sync") ||
                            path.contains("social-connect") ||
                            path.contains("melody/v1/check")

                    if (host.contains("spclient") && isDetection) {
                        Logger.printDebug { "★ L2: Blocco Detection Path -> $path" }

                        val protocolClass = Class.forName("okhttp3.Protocol", false, cl)
                        val http11 = protocolClass.getField("HTTP_1_1").get(null)
                        val builder = builderClass.getConstructor().newInstance()

                        findMethodSafe(builderClass, "request", reqClass)?.invoke(builder, req)
                        findMethodSafe(builderClass, "protocol", protocolClass)?.invoke(builder, http11)
                        findMethodSafe(builderClass, "code", Int::class.java)?.invoke(builder, 204)
                        findMethodSafe(builderClass, "message", String::class.java)?.invoke(builder, "Blocked")

                        val emptyBody = findMethodSafe(bodyClass, "create", mtClass, String::class.java)?.invoke(null, null, "")
                        findMethodSafe(builderClass, "body", bodyClass)?.invoke(builder, emptyBody)

                        param.result = findMethodSafe(builderClass, "build")?.invoke(builder)
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val resp = param.result ?: return@runCatching
                    val code = findMethodSafe(resp.javaClass, "code")?.invoke(resp) as? Int ?: return@runCatching
                    val req = findMethodSafe(resp.javaClass, "request")?.invoke(resp) ?: return@runCatching
                    val url = findMethodSafe(req.javaClass, "url")?.invoke(req) ?: return@runCatching
                    val host = findMethodSafe(url.javaClass, "host")?.invoke(url) as? String ?: ""

                    val isAuthEndpoint = host.contains("login5") || host.contains("googleusercontent")

                    // LAYER 1: Salvataggio Sessione (200) o Ripristino su Errore (401/403)
                    if (code in 200..299 && isAuthEndpoint) {
                        val peeked = runCatching {
                            resp.javaClass.getMethod("peekBody", Long::class.javaPrimitiveType).invoke(resp, 65536L)
                        }.getOrNull() ?: return@runCatching

                        val text = findMethodSafe(peeked.javaClass, "string")?.invoke(peeked) as? String
                        if (text?.contains("access_token") == true) {
                            AuthCache.body = text
                            AuthCache.contentType = findMethodSafe(peeked.javaClass, "contentType")?.invoke(peeked)
                            Logger.printDebug { "★ L1: Sessione salvata in cache" }
                        }
                    } else if ((code == 401 || code == 403) && AuthCache.body != null && isAuthEndpoint) {
                        Logger.printDebug { "★ L1: Spotify ha tentato il logout ($code). Re-injecting session..." }

                        val builder = findMethodSafe(resp.javaClass, "newBuilder")?.invoke(resp) ?: return@runCatching
                        findMethodSafe(builder.javaClass, "code", Int::class.java)?.invoke(builder, 200)
                        findMethodSafe(builder.javaClass, "message", String::class.java)?.invoke(builder, "OK")

                        val replayBody = findMethodSafe(bodyClass, "create", mtClass, String::class.java)
                            ?.invoke(null, AuthCache.contentType, AuthCache.body)
                        findMethodSafe(builder.javaClass, "body", bodyClass)?.invoke(builder, replayBody)

                        param.result = findMethodSafe(builder.javaClass, "build")?.invoke(builder)
                    }
                }
            }
        })
    }.onFailure { Logger.printDebug { "L1/L2 Hook Failure: ${it.message}" } }

    // --- LAYER 3: Protezione Locale (SharedPreferences) ---
    runCatching {
        val editorClass = Class.forName("android.app.SharedPreferencesImpl\$EditorImpl", false, cl)
        val protectedKeys = setOf("token", "session", "auth", "login", "account", "credential")

        XposedHelpers.findAndHookMethod(editorClass, "remove", String::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val key = param.args[0] as? String ?: return
                if (protectedKeys.any { key.lowercase().contains(it) }) {
                    Logger.printDebug { "★ L3: Impedita rimozione chiave di sessione: $key" }
                    param.result = param.thisObject
                }
            }
        })

        XposedHelpers.findAndHookMethod(editorClass, "clear", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                Logger.printDebug { "★ L3: Impedito SharedPreferences.clear() per salvare il login" }
                param.result = param.thisObject
            }
        })
    }.onFailure { Logger.printDebug { "L3 Hook Failure: ${it.message}" } }
}