@file:Suppress("unused")

package io.github.chsbuffer.revancedxposed

import dalvik.system.BaseDexClassLoader
import io.github.chsbuffer.revancedxposed.ChimeraBridge
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import java.util.Enumeration
import de.robv.android.xposed.XposedHelpers

typealias MethodHookParam = ChimeraBridge.MethodHookParam
typealias Replacer = (MethodHookParam) -> Any?

inline fun <T, R> T.runCatchingOrNull(func: T.() -> R?) = try {
    func()
} catch (e: Throwable) {
    null
}

fun Any.getObjectField(field: String?): Any? = XposedHelpers.getObjectField(this, field)

fun Any.getObjectFieldOrNull(field: String?): Any? = runCatchingOrNull {
    XposedHelpers.getObjectField(this, field)
}

@Suppress("UNCHECKED_CAST")
fun <T> Any.getObjectFieldAs(field: String?) = XposedHelpers.getObjectField(this, field) as T

@Suppress("UNCHECKED_CAST")
fun <T> Any.getObjectFieldOrNullAs(field: String?) = runCatchingOrNull {
    XposedHelpers.getObjectField(this, field) as T
}

fun Any.getIntField(field: String?) = XposedHelpers.getIntField(this, field)

fun Any.getIntFieldOrNull(field: String?) = runCatchingOrNull {
    XposedHelpers.getIntField(this, field)
}

fun Any.getLongField(field: String?) = XposedHelpers.getLongField(this, field)

fun Any.getLongFieldOrNull(field: String?) = runCatchingOrNull {
    XposedHelpers.getLongField(this, field)
}

fun Any.getBooleanFieldOrNull(field: String?) = runCatchingOrNull {
    XposedHelpers.getBooleanField(this, field)
}

fun Any.callMethod(methodName: String?, vararg args: Any?): Any? =
    XposedHelpers.callMethod(this, methodName, *args)

fun Any.callMethodOrNull(methodName: String?, vararg args: Any?): Any? = runCatchingOrNull {
    XposedHelpers.callMethod(this, methodName, *args)
}

fun Class<*>.callStaticMethod(methodName: String?, vararg args: Any?): Any? =
    XposedHelpers.callStaticMethod(this, methodName, *args)

fun Class<*>.callStaticMethodOrNull(methodName: String?, vararg args: Any?): Any? =
    runCatchingOrNull {
        XposedHelpers.callStaticMethod(this, methodName, *args)
    }

@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.callStaticMethodAs(methodName: String?, vararg args: Any?) =
    XposedHelpers.callStaticMethod(this, methodName, *args) as T

@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.callStaticMethodOrNullAs(methodName: String?, vararg args: Any?) =
    runCatchingOrNull {
        XposedHelpers.callStaticMethod(this, methodName, *args) as T
    }

@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.getStaticObjectFieldAs(field: String?) = XposedHelpers.getStaticObjectField(this, field) as T

@Suppress("UNCHECKED_CAST")
fun <T> Class<*>.getStaticObjectFieldOrNullAs(field: String?) = runCatchingOrNull {
    XposedHelpers.getStaticObjectField(this, field) as T
}

fun Class<*>.getStaticObjectField(field: String?): Any? = XposedHelpers.getStaticObjectField(this, field)

fun Class<*>.getStaticObjectFieldOrNull(field: String?): Any? = runCatchingOrNull {
    XposedHelpers.getStaticObjectField(this, field)
}

fun Class<*>.setStaticObjectField(field: String?, obj: Any?) = apply {
    XposedHelpers.setStaticObjectField(this, field, obj)
}

fun Class<*>.setStaticObjectFieldIfExist(field: String?, obj: Any?) = apply {
    try {
        XposedHelpers.setStaticObjectField(this, field, obj)
    } catch (ignored: Throwable) {
    }
}

inline fun <reified T> Class<*>.findFieldByExactType(): Field? =
    XposedHelpers.findFirstFieldByExactType(this, T::class.java)

fun Class<*>.findFieldByExactType(type: Class<*>): Field? =
    XposedHelpers.findFirstFieldByExactType(this, type)

@Suppress("UNCHECKED_CAST")
fun <T> Any.callMethodAs(methodName: String?, vararg args: Any?) =
    XposedHelpers.callMethod(this, methodName, *args) as T

@Suppress("UNCHECKED_CAST")
fun <T> Any.callMethodOrNullAs(methodName: String?, vararg args: Any?) = runCatchingOrNull {
    XposedHelpers.callMethod(this, methodName, *args) as T
}

fun Any.callMethod(methodName: String?, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? =
    XposedHelpers.callMethod(this, methodName, parameterTypes, *args)

fun Any.callMethodOrNull(
    methodName: String?,
    parameterTypes: Array<Class<*>>,
    vararg args: Any?
): Any? = runCatchingOrNull {
    XposedHelpers.callMethod(this, methodName, parameterTypes, *args)
}

fun Class<*>.callStaticMethod(
    methodName: String?,
    parameterTypes: Array<Class<*>>,
    vararg args: Any?
): Any? = XposedHelpers.callStaticMethod(this, methodName, parameterTypes, *args)

fun Class<*>.callStaticMethodOrNull(
    methodName: String?,
    parameterTypes: Array<Class<*>>,
    vararg args: Any?
): Any? = runCatchingOrNull {
    XposedHelpers.callStaticMethod(this, methodName, parameterTypes, *args)
}

fun String.findClass(classLoader: ClassLoader?): Class<*> = XposedHelpers.findClass(this, classLoader)

infix fun String.on(classLoader: ClassLoader?): Class<*> = XposedHelpers.findClass(this, classLoader)

fun String.findClassOrNull(classLoader: ClassLoader?): Class<*>? =
    XposedHelpers.findClassIfExists(this, classLoader)

infix fun String.from(classLoader: ClassLoader?): Class<*>? =
    XposedHelpers.findClassIfExists(this, classLoader)

fun Class<*>.new(vararg args: Any?): Any = XposedHelpers.newInstance(this, *args)

fun Class<*>.new(parameterTypes: Array<Class<*>>, vararg args: Any?): Any =
    XposedHelpers.newInstance(this, parameterTypes, *args)

fun Class<*>.findField(field: String?): Field = XposedHelpers.findField(this, field)

fun Class<*>.findFieldOrNull(field: String?): Field? = XposedHelpers.findFieldIfExists(this, field)

fun <T> T.setIntField(field: String?, value: Int) = apply {
    XposedHelpers.setIntField(this, field, value)
}

fun <T> T.setLongField(field: String?, value: Long) = apply {
    XposedHelpers.setLongField(this, field, value)
}

fun <T> T.setObjectField(field: String?, value: Any?) = apply {
    XposedHelpers.setObjectField(this, field, value)
}

fun <T> T.setBooleanField(field: String?, value: Boolean) = apply {
    XposedHelpers.setBooleanField(this, field, value)
}

fun <T> T.setFloatField(field: String?, value: Float) = apply {
    XposedHelpers.setFloatField(this, field, value)
}

fun Class<*>.findFirstFieldByExactType(type: Class<*>): Field =
    XposedHelpers.findFirstFieldByExactType(this, type)

fun Class<*>.findFirstFieldByExactTypeOrNull(type: Class<*>?): Field? = runCatchingOrNull {
    XposedHelpers.findFirstFieldByExactType(this, type)
}

fun Any.getFirstFieldByExactType(type: Class<*>): Any? =
    javaClass.findFirstFieldByExactType(type).get(this)

@Suppress("UNCHECKED_CAST")
fun <T> Any.getFirstFieldByExactTypeAs(type: Class<*>) =
    javaClass.findFirstFieldByExactType(type).get(this) as? T

inline fun <reified T : Any> Any.getFirstFieldByExactType() =
    javaClass.findFirstFieldByExactType(T::class.java).get(this) as? T

fun Any.getFirstFieldByExactTypeOrNull(type: Class<*>?): Any? = runCatchingOrNull {
    javaClass.findFirstFieldByExactTypeOrNull(type)?.get(this)
}

@Suppress("UNCHECKED_CAST")
fun <T> Any.getFirstFieldByExactTypeOrNullAs(type: Class<*>?) =
    getFirstFieldByExactTypeOrNull(type) as? T

inline fun <reified T> Any.getFirstFieldByExactTypeOrNull() =
    getFirstFieldByExactTypeOrNull(T::class.java) as? T

inline fun ClassLoader.findDexClassLoader(crossinline delegator: (BaseDexClassLoader) -> BaseDexClassLoader = { x -> x }): BaseDexClassLoader? {
    var classLoader = this
    while (classLoader !is BaseDexClassLoader) {
        if (classLoader.parent != null) classLoader = classLoader.parent
        else return null
    }
    return delegator(classLoader)
}

inline fun ClassLoader.allClassesList(crossinline delegator: (BaseDexClassLoader) -> BaseDexClassLoader = { x -> x }): List<String> {
    return findDexClassLoader(delegator)?.let { dcl ->
        XposedHelpers.getObjectField(dcl, "pathList")
            ?.let { pathList ->
                (XposedHelpers.getObjectField(pathList, "dexElements") as? Array<Any>)?.flatMap {
                    XposedHelpers.getObjectField(it, "dexFile")
                        ?.let { dexFile ->
                            (XposedHelpers.callMethod(dexFile, "entries") as? Enumeration<String>)?.toList()
                        }.orEmpty()
                }
            }
    }.orEmpty()
}

val Member.isStatic: Boolean
    inline get() = Modifier.isStatic(modifiers)
val Member.isFinal: Boolean
    inline get() = Modifier.isFinal(modifiers)
val Member.isPublic: Boolean
    inline get() = Modifier.isPublic(modifiers)
val Member.isNotStatic: Boolean
    inline get() = !isStatic
val Member.isAbstract: Boolean
    inline get() = Modifier.isAbstract(modifiers)
val Member.isPrivate: Boolean
    inline get() = Modifier.isPrivate(modifiers)
val Class<*>.isAbstract: Boolean
    inline get() = !isPrimitive && Modifier.isAbstract(modifiers)
val Class<*>.isFinal: Boolean
    inline get() = !isPrimitive && Modifier.isFinal(modifiers)
