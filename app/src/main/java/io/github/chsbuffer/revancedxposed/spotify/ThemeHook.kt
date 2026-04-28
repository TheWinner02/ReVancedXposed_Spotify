package io.github.chsbuffer.revancedxposed.spotify

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Color
import android.view.View
import io.github.chsbuffer.revancedxposed.*
import androidx.core.graphics.toColorInt
import kotlin.math.abs

class ThemeHook(app: Application) {

    private val colorCache = HashMap<Int, Int>()
    private val res = app.resources
    private val classLoader = app.classLoader

    // --- COLORI MONET ---
    @SuppressLint("DiscouragedApi")
    private val primaryBg = try {
        app.getColor(res.getIdentifier("system_neutral1_900", "color", "android"))
    } catch (_: Exception) { Color.BLACK }

    @SuppressLint("DiscouragedApi")
    private val secondaryBg = try {
        app.getColor(res.getIdentifier("system_neutral1_800", "color", "android"))
    } catch (_: Exception) {
        "#121212".toColorInt() }

    @SuppressLint("DiscouragedApi")
    private val accent = try {
        app.getColor(res.getIdentifier("system_accent1_200", "color", "android"))
    } catch (_: Exception) {
        "#1DB954".toColorInt() }

    // NUOVO: Valore per lo stato "Pressed"
    @SuppressLint("DiscouragedApi")
    private val accentPressed = try {
        app.getColor(res.getIdentifier("system_accent1_400", "color", "android"))
    } catch (_: Exception) {
        "#1ABC54".toColorInt() }

    fun hook() {
        // 1. PorterDuffColorFilter
        runCatching {
            val clazz = "android.graphics.PorterDuffColorFilter".findClass(classLoader)
            val ctor = clazz.getDeclaredConstructor(Int::class.javaPrimitiveType!!, android.graphics.PorterDuff.Mode::class.java)
            ChimeraBridge.hookMethod(ctor, object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    param.args?.set(0, replaceColorLogic(param.args?.get(0) as Int))
                }
            })
        }

        // 2. ColorStateList
        runCatching {
            val clazz = "android.content.res.ColorStateList".findClass(classLoader)
            val method = clazz.getDeclaredMethodRecursive("getColorForState", IntArray::class.java, Int::class.javaPrimitiveType!!)
            ChimeraBridge.hookMethod(method, object : ChimeraBridge.XC_MethodHook() {
                override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    val states = param.args?.get(0) as IntArray
                    val originalColor = param.result as Int

                    val isPressed = states.contains(android.R.attr.state_pressed)
                    val isSelected = states.contains(android.R.attr.state_selected)
                    val isFocused = states.contains(android.R.attr.state_focused)

                    param.setResult(when {
                        isPressed || isFocused -> {
                            Color.argb(77, Color.red(accentPressed), Color.green(accentPressed), Color.blue(accentPressed))
                        }
                        isSelected -> accent
                        else -> replaceColorLogic(originalColor)
                    })
                }
            })
        }

        // 3. Color.parseColor
        runCatching {
            val clazz = Color::class.java
            val method = clazz.getDeclaredMethodRecursive("parseColor", String::class.java)
            ChimeraBridge.hookMethod(method, object : ChimeraBridge.XC_MethodHook() {
                override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    param.setResult(replaceColorLogic(param.result as Int))
                }
            })
        }

        // 4. Paint.setColor
        runCatching {
            val clazz = android.graphics.Paint::class.java
            val method = clazz.getDeclaredMethodRecursive("setColor", Int::class.javaPrimitiveType!!)
            ChimeraBridge.hookMethod(method, object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    param.args?.set(0, replaceColorLogic(param.args?.get(0) as Int))
                }
            })
        }

        // 5. Resources.getColor
        runCatching {
            val clazz = android.content.res.Resources::class.java
            val themeClass = "android.content.res.Resources\$Theme".findClass(classLoader)
            val method = clazz.getDeclaredMethodRecursive("getColor", Int::class.javaPrimitiveType!!, themeClass)
            ChimeraBridge.hookMethod(method, object : ChimeraBridge.XC_MethodHook() {
                override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    param.setResult(replaceColorLogic(param.result as Int))
                }
            })
        }

        // 6. TypedArray.getColor
        runCatching {
            val clazz = android.content.res.TypedArray::class.java
            val method = clazz.getDeclaredMethodRecursive("getColor", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            ChimeraBridge.hookMethod(method, object : ChimeraBridge.XC_MethodHook() {
                override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    param.setResult(replaceColorLogic(param.result as Int))
                }
            })
        }

        // 7. View.onAttachedToWindow (Remozione Ombre)
        runCatching {
            val clazz = View::class.java
            val method = clazz.getDeclaredMethodRecursive("onAttachedToWindow")
            ChimeraBridge.hookMethod(method, object : ChimeraBridge.XC_MethodHook() {
                override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    val view = param.thisObject as View
                    val resName = try { view.resources.getResourceEntryName(view.id).lowercase() } catch (_: Exception) { "" }
                    val isShadowOrFade = resName.contains("shadow") || resName.contains("fade") || resName.contains("gradient")
                    if (isShadowOrFade && view.javaClass.name == "android.view.View") {
                        view.visibility = View.GONE
                        view.layoutParams.width = 0
                        view.layoutParams.height = 0
                    }
                    if (view.javaClass.name.contains("RecyclerView")) {
                        view.setObjectField("mHorizontalFadingEdgeEnabled", false)
                        view.setObjectField("mVerticalFadingEdgeEnabled", false)
                    }
                }
            })
        }
    }

    private fun replaceColorLogic(color: Int): Int {
        if (color == Color.TRANSPARENT) return 0
        colorCache[color]?.let { return it }

        val a = Color.alpha(color)
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        val newColor = when {
            (g > 100 && g > r * 1.1 && g > b * 1.1) -> accent
            (r > 150 && abs(r - g) < 20 && abs(r - b) < 20) -> accent
            (r <= 25 && g <= 25 && b <= 25) -> primaryBg
            (r in 26..70 && g in 26..70 && b in 26..70) -> secondaryBg
            else -> color
        }

        val finalColor = Color.argb(a, Color.red(newColor), Color.green(newColor), Color.blue(newColor))
        colorCache[color] = finalColor
        return finalColor
    }
}
