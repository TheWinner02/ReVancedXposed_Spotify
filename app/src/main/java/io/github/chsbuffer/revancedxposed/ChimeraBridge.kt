package io.github.chsbuffer.revancedxposed

import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * Compatibility layer to mimic Xposed API using Pine engine.
 */
object ChimeraBridge {

    private data class ParsedMethodHook(
        val parameterTypes: Array<Class<*>>,
        val callback: XC_MethodHook
    )

    abstract class XC_MethodHook {
        open fun beforeHookedMethod(param: MethodHookParam) {}
        open fun afterHookedMethod(param: MethodHookParam) {}
    }

    class MethodHookParam {
        @JvmField var method: Member? = null
        @JvmField var thisObject: Any? = null
        @JvmField var args: Array<Any?>? = null

        @JvmField var result: Any? = null
        @JvmField var throwable: Throwable? = null
        @JvmField var returnEarly: Boolean = false

        fun getResult(): Any? = result
        fun setResult(result: Any?) {
            this.result = result
            this.returnEarly = true
        }

        fun getThrowable(): Throwable? = throwable
        fun setThrowable(throwable: Throwable?) {
            this.throwable = throwable
            this.returnEarly = true
        }

        fun hasResult(): Boolean = returnEarly || result != null
        fun hasThrowable(): Boolean = throwable != null
    }

    fun hookMethod(method: Member, callback: XC_MethodHook) {
        Pine.hook(method, object : MethodHook() {
            override fun beforeCall(topParam: Pine.CallFrame) {
                val param = MethodHookParam().apply {
                    this.method = topParam.method
                    this.thisObject = topParam.thisObject
                    this.args = topParam.args
                }
                callback.beforeHookedMethod(param)
                if (param.returnEarly) {
                    if (param.throwable != null) {
                        topParam.setThrowable(param.throwable)
                    } else {
                        topParam.setResult(param.result)
                    }
                }
            }

            override fun afterCall(topParam: Pine.CallFrame) {
                val param = MethodHookParam().apply {
                    this.method = topParam.method
                    this.thisObject = topParam.thisObject
                    this.args = topParam.args
                    this.result = topParam.result
                    this.throwable = topParam.throwable
                }

                param.returnEarly = false
                callback.afterHookedMethod(param)

                if (param.returnEarly) {
                    if (param.throwable != null) {
                        topParam.setThrowable(param.throwable)
                    } else {
                        topParam.setResult(param.result)
                    }
                }
            }
        })
    }

    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any
    ): Method {
        val parsed = parseParameterTypesAndCallback(clazz, parameterTypesAndCallback)
        val method = clazz.getDeclaredMethod(methodName, *parsed.parameterTypes)
        hookMethod(method, parsed.callback)
        return method
    }

    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader?,
        methodName: String,
        vararg parameterTypesAndCallback: Any
    ): Method {
        val clazz = Class.forName(className, false, classLoader)
        return findAndHookMethod(clazz, methodName, *parameterTypesAndCallback)
    }

    fun hookAllMethods(
        clazz: Class<*>,
        methodName: String,
        callback: XC_MethodHook
    ): Set<Member> {
        val hooked = LinkedHashSet<Member>()
        clazz.declaredMethods
            .asSequence()
            .filter { it.name == methodName }
            .forEach {
                hookMethod(it, callback)
                hooked.add(it)
            }
        return hooked
    }

    fun hookAllMethods(
        className: String,
        classLoader: ClassLoader?,
        methodName: String,
        callback: XC_MethodHook
    ): Set<Member> {
        val clazz = Class.forName(className, false, classLoader)
        return hookAllMethods(clazz, methodName, callback)
    }

    private fun parseParameterTypesAndCallback(
        ownerClass: Class<*>,
        parameterTypesAndCallback: Array<out Any>
    ): ParsedMethodHook {
        require(parameterTypesAndCallback.isNotEmpty()) {
            "parameterTypesAndCallback must include at least one XC_MethodHook callback"
        }
        val callback = parameterTypesAndCallback.last() as? XC_MethodHook
            ?: throw IllegalArgumentException("Last argument must be XC_MethodHook")
        val parameterTypes = parameterTypesAndCallback
            .dropLast(1)
            .map { resolveParameterType(ownerClass, it) }
            .toTypedArray()
        return ParsedMethodHook(parameterTypes, callback)
    }

    private fun resolveParameterType(ownerClass: Class<*>, typeArg: Any): Class<*> {
        return when (typeArg) {
            is Class<*> -> typeArg
            is String -> Class.forName(typeArg, false, ownerClass.classLoader)
            else -> throw IllegalArgumentException(
                "Parameter type must be Class<*> or String, got ${typeArg::class.java.name}"
            )
        }
    }

    fun log(message: String) {
        android.util.Log.i("BpsRuntime", message)
    }

    fun log(t: Throwable) {
        android.util.Log.e("BpsRuntime", "Error", t)
    }
}