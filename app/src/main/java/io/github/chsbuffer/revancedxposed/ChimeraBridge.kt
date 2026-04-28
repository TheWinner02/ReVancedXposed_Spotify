package io.github.chsbuffer.revancedxposed

import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook
import java.lang.reflect.Member

/**
 * Compatibility layer to mimic Xposed API using Pine engine.
 */
object ChimeraBridge {

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

    fun log(message: String) {
        android.util.Log.i("Chimera:Bridge", message)
    }

    fun log(t: Throwable) {
        android.util.Log.e("Chimera:Bridge", "Error", t)
    }
}
