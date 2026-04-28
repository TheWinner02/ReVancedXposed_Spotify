package io.github.chsbuffer.revancedxposed

import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import de.robv.android.xposed.IXposedHookZygoteInit
import io.github.chsbuffer.revancedxposed.ChimeraBridge
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member

typealias IScopedHookCallback = ScopedHookParam.(ChimeraBridge.MethodHookParam) -> Unit
typealias IHookCallback = (ChimeraBridge.MethodHookParam) -> Unit

class HookDsl<TCallback>(emptyCallback: TCallback) {
    var before: TCallback = emptyCallback
    var after: TCallback = emptyCallback

    fun before(f: TCallback) {
        this.before = f
    }

    fun after(f: TCallback) {
        this.after = f
    }
}

inline fun Member.hookMethod(crossinline block: HookDsl<IHookCallback>.() -> Unit) {
    val builder = HookDsl<IHookCallback> {}.apply(block)
    hookMethodInternal(builder.before, builder.after)
}

inline fun Member.hookMethodInternal(
    crossinline before: IHookCallback, crossinline after: IHookCallback
) {
    ChimeraBridge.hookMethod(this, object : ChimeraBridge.XC_MethodHook() {
        override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
            before(param)
        }

        override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
            after(param)
        }
    })
}

@JvmInline
value class ScopedHookParam(val outerParam: ChimeraBridge.MethodHookParam)

fun scopedHook(vararg pairs: Pair<Member, HookDsl<IScopedHookCallback>.() -> Unit>): ChimeraBridge.XC_MethodHook {
    val hook = ScopedHook()
    pairs.forEach { (member, block) ->
        val builder = HookDsl<IScopedHookCallback> {}.apply(block)
        hook.hookInnerMethod(member, builder.before, builder.after)
    }
    return hook
}

inline fun scopedHook(
    hookMethod: Member, crossinline f: HookDsl<IScopedHookCallback>.() -> Unit
): ChimeraBridge.XC_MethodHook {
    val hook = ScopedHook()
    val builder = HookDsl<IScopedHookCallback> {}.apply(f)
    hook.hookInnerMethod(hookMethod, builder.before, builder.after)
    return hook
}

class ScopedHook : ChimeraBridge.XC_MethodHook() {
    inline fun hookInnerMethod(
        hookMethod: Member,
        crossinline before: IScopedHookCallback,
        crossinline after: IScopedHookCallback
    ) {
        ChimeraBridge.hookMethod(hookMethod, object : ChimeraBridge.XC_MethodHook() {
            override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                val outerParam = outerParam.get() ?: return
                before(ScopedHookParam(outerParam), param)
            }

            override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                val outerParam = outerParam.get() ?: return
                after(ScopedHookParam(outerParam), param)
            }
        })
    }

    val outerParam: ThreadLocal<ChimeraBridge.MethodHookParam> = ThreadLocal<ChimeraBridge.MethodHookParam>()

    override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
        outerParam.set(param)
    }

    override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
        outerParam.remove()
    }
}

fun injectHostClassLoaderToSelf(self: ClassLoader, host: ClassLoader) {
    // Reflection used here should be standard Java reflection
    try {
        val parentField = ClassLoader::class.java.getDeclaredField("parent")
        parentField.isAccessible = true
        parentField.set(self, object : ClassLoader(self.parent) {
            override fun findClass(name: String): Class<*> {
                try {
                    val findClassMethod = ClassLoader::class.java.getDeclaredMethod("findClass", String::class.java)
                    findClassMethod.isAccessible = true
                    return findClassMethod.invoke(self, name) as Class<*>
                } catch (_: Exception) {
                }

                try {
                    return host.loadClass(name)
                } catch (_: ClassNotFoundException) {
                }

                throw ClassNotFoundException(name)
            }
        })
    } catch (e: Exception) {
        ChimeraBridge.log(e)
    }
}

@Suppress("UNCHECKED_CAST")
fun Class<*>.enumValueOf(name: String): Enum<*>? {
    return try {
        java.lang.Enum.valueOf(this as Class<out Enum<*>>, name)
    } catch (_: IllegalArgumentException) {
        null
    }
}