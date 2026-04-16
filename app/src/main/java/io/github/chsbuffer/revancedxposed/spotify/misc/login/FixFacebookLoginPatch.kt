package io.github.chsbuffer.revancedxposed.spotify.misc.login

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import java.lang.reflect.Method
import java.util.Enumeration

@Suppress("unused")
fun SpotifyHook.FixFacebookLoginPatch() {
    val TAG = "FixFacebookLogin"

    runCatching {
        val cl = classLoader

        XposedBridge.log("$TAG -> init")

        // 🔍 Scan dex → equivalente fingerprint "katana_proxy_auth"
        val targetClass = findKatanaProxyClass(cl)
            ?: run {
                XposedBridge.log("$TAG -> class not found")
                return
            }

        // 🔍 Trova metodo → equivalente "tryAuthorize"
        val targetMethod = targetClass.declaredMethods.firstOrNull { method ->
            method.returnType == Int::class.javaPrimitiveType &&
                    method.parameterTypes.isNotEmpty()
        } ?: run {
            XposedBridge.log("$TAG -> method not found")
            return
        }

        XposedBridge.log("$TAG -> Hooking ${targetClass.name}.${targetMethod.name}")

        // 🔥 equivalente returnEarly(0)
        XposedBridge.hookMethod(targetMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = 0
            }
        })

    }.onFailure {
        XposedBridge.log("$TAG error -> ${it.message}")
    }
}

// ══════════════════════════════════════════════════════
// INTERNAL
// ══════════════════════════════════════════════════════

private fun findKatanaProxyClass(classLoader: ClassLoader): Class<*>? {
    return runCatching {
        val baseDex = Class.forName("dalvik.system.BaseDexClassLoader")
        val pathListField = baseDex.getDeclaredField("pathList").apply { isAccessible = true }
        val pathList = pathListField.get(classLoader)

        val dexElementsField = pathList.javaClass
            .getDeclaredField("dexElements")
            .apply { isAccessible = true }

        val dexElements = dexElementsField.get(pathList) as Array<*>

        for (element in dexElements) {
            val dexFile = element?.javaClass
                ?.getDeclaredField("dexFile")
                ?.apply { isAccessible = true }
                ?.get(element) ?: continue

            val entries = dexFile.javaClass
                .getMethod("entries")
                .invoke(dexFile) as Enumeration<*>

            while (entries.hasMoreElements()) {
                val className = entries.nextElement() as String

                try {
                    val clazz = Class.forName(className, false, classLoader)

                    // 🔥 fingerprint reale
                    if (clazz.declaredFields.any { field ->
                            field.type == String::class.java &&
                                    field.tryGetStaticValue() == "katana_proxy_auth"
                        }
                    ) {
                        XposedBridge.log("FixFacebookLogin -> Found class: $className")
                        return clazz
                    }

                } catch (_: Throwable) {}
            }
        }

        null
    }.getOrNull()
}

private fun java.lang.reflect.Field.tryGetStaticValue(): Any? {
    return runCatching {
        isAccessible = true
        get(null)
    }.getOrNull()
}