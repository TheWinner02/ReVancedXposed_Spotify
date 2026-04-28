@file:Suppress("unused")

package io.github.chsbuffer.revancedxposed

import io.github.chsbuffer.revancedxposed.ChimeraBridge
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Constructor
import java.util.Enumeration

/**
 * Re-implementation of XposedHelpers using standard Java Reflection.
 * This allows the module to run standalone without de.robv.android.xposed at runtime.
 */
typealias MethodHookParam = ChimeraBridge.MethodHookParam

inline fun <T, R> T.runCatchingOrNull(func: T.() -> R?) = try {
    func()
} catch (e: Throwable) {
    null
}

// --- FIELD ACCESS ---

fun Any.getObjectField(fieldName: String): Any? {
    val field = javaClass.getDeclaredFieldRecursive(fieldName)
    field.isAccessible = true
    return field.get(this)
}

fun Any.getObjectFieldOrNull(fieldName: String): Any? = runCatching { getObjectField(fieldName) }.getOrNull()

@Suppress("UNCHECKED_CAST")
fun <T> Any.getObjectFieldAs(fieldName: String) = getObjectField(fieldName) as T

fun Any.setIntField(fieldName: String, value: Int) {
    val field = javaClass.getDeclaredFieldRecursive(fieldName)
    field.isAccessible = true
    field.setInt(this, value)
}

fun Any.setObjectField(fieldName: String, value: Any?) {
    val field = javaClass.getDeclaredFieldRecursive(fieldName)
    field.isAccessible = true
    field.set(this, value)
}

fun Any.setBooleanField(fieldName: String, value: Boolean) {
    val field = javaClass.getDeclaredFieldRecursive(fieldName)
    field.isAccessible = true
    field.setBoolean(this, value)
}

// --- METHOD CALLS ---

fun Any.callMethod(methodName: String, vararg args: Any?): Any? {
    val parameterTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
    val method = javaClass.getDeclaredMethodRecursive(methodName, *parameterTypes)
    method.isAccessible = true
    return method.invoke(this, *args)
}

fun Any.callMethod(methodName: String, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? {
    val method = javaClass.getDeclaredMethodRecursive(methodName, *parameterTypes)
    method.isAccessible = true
    return method.invoke(this, *args)
}

fun Any.callMethodOrNull(methodName: String, vararg args: Any?): Any? = runCatching { callMethod(methodName, *args) }.getOrNull()

@Suppress("UNCHECKED_CAST")
fun <T> Any.callMethodAs(methodName: String, vararg args: Any?) = callMethod(methodName, *args) as T

fun Class<*>.callStaticMethod(methodName: String, vararg args: Any?): Any? {
    val parameterTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
    val method = getDeclaredMethodRecursive(methodName, *parameterTypes)
    method.isAccessible = true
    return method.invoke(null, *args)
}

fun Class<*>.callStaticMethod(methodName: String, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? {
    val method = getDeclaredMethodRecursive(methodName, *parameterTypes)
    method.isAccessible = true
    return method.invoke(null, *args)
}

// --- STATIC ACCESS ---

fun Class<*>.getStaticObjectField(fieldName: String): Any? {
    val field = getDeclaredFieldRecursive(fieldName)
    field.isAccessible = true
    return field.get(null)
}

fun Class<*>.setStaticObjectField(fieldName: String, value: Any?) {
    val field = getDeclaredFieldRecursive(fieldName)
    field.isAccessible = true
    field.set(null, value)
}

// --- HELPER INTERNAL ---

fun Class<*>.getDeclaredFieldRecursive(fieldName: String): Field {
    var clazz: Class<*>? = this
    while (clazz != null) {
        try {
            return clazz.getDeclaredField(fieldName)
        } catch (e: NoSuchFieldException) {
            clazz = clazz.superclass
        }
    }
    throw NoSuchFieldException(fieldName)
}

fun Class<*>.getDeclaredMethodRecursive(methodName: String, vararg parameterTypes: Class<*>): Method {
    var clazz: Class<*>? = this
    while (clazz != null) {
        try {
            return clazz.getDeclaredMethod(methodName, *parameterTypes)
        } catch (e: NoSuchMethodException) {
            clazz = clazz.superclass
        }
    }
    throw NoSuchMethodException("$name#$methodName")
}

fun Class<*>.findFirstFieldByExactType(type: Class<*>): Field {
    var clazz: Class<*>? = this
    while (clazz != null) {
        for (field in clazz.declaredFields) {
            if (field.type == type) return field
        }
        clazz = clazz.superclass
    }
    throw NoSuchFieldException("Field with type ${type.name} not found")
}

// --- CLASS UTILS ---

fun String.findClass(classLoader: ClassLoader?): Class<*> = Class.forName(this, false, classLoader)

fun String.findClassOrNull(classLoader: ClassLoader?): Class<*>? = runCatching { findClass(classLoader) }.getOrNull()

// --- MEMBER UTILS ---

val Member.isStatic: Boolean get() = Modifier.isStatic(modifiers)
val Member.isFinal: Boolean get() = Modifier.isFinal(modifiers)
val Member.isPublic: Boolean get() = Modifier.isPublic(modifiers)
val Member.isNotStatic: Boolean get() = !isStatic
val Member.isAbstract: Boolean get() = Modifier.isAbstract(modifiers)
val Member.isPrivate: Boolean get() = Modifier.isPrivate(modifiers)
val Class<*>.isAbstract: Boolean get() = !isPrimitive && Modifier.isAbstract(modifiers)
val Class<*>.isFinal: Boolean get() = !isPrimitive && Modifier.isFinal(modifiers)
