package io.github.chsbuffer.revancedxposed.spotify

import android.content.res.Resources
import android.graphics.Outline
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import io.github.chsbuffer.revancedxposed.ChimeraBridge
import de.robv.android.xposed.XposedHelpers

class RoundyUIHook(private val classLoader: ClassLoader) {

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            Resources.getSystem().displayMetrics
        )
    }

    private val radiusLarge = dpToPx(28f)
    private val radiusFull = dpToPx(100f)

    fun hook() {
        // 1. Hook UNIVERSALE per lo stondamento
        runCatching<Unit> {
            ChimeraBridge.hookMethod(
                XposedHelpers.findMethodExact("android.view.View", classLoader, "onAttachedToWindow"),
                object : ChimeraBridge.XC_MethodHook() {
                    override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                        applyRoundingIfTarget(param.thisObject as View)
                    }
                }
            )
        }

        // 2. Hook ImageView
        runCatching {
            ChimeraBridge.hookMethod(
                XposedHelpers.findMethodExact("android.widget.ImageView", classLoader, "setImageDrawable", android.graphics.drawable.Drawable::class.java),
                object : ChimeraBridge.XC_MethodHook() {
                    override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
                        applyRoundingIfTarget(param.thisObject as ImageView)
                    }
                }
            )
        }

        // 3. GradientDrawable
        runCatching {
            ChimeraBridge.hookMethod(
                XposedHelpers.findMethodExact("android.graphics.drawable.GradientDrawable", classLoader, "setCornerRadius", Float::class.javaPrimitiveType),
                object : ChimeraBridge.XC_MethodHook() {
                    override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                        val original = param.args?.get(0) as Float
                        param.setResult(if (original > dpToPx(15f)) radiusFull else radiusLarge)
                    }
                }
            )
        }
    }

    private fun applyRoundingIfTarget(view: View) {
        val resName = try { view.resources.getResourceEntryName(view.id) } catch (_: Exception) { "" }
        val className = view.javaClass.name.lowercase()

        val isAvatar = className.contains("faceview") || resName.contains("face")
        val isImage = view is ImageView || className.contains("imageview") && !isAvatar
        val isCoverOrHeader = resName.contains("header") || resName.contains("cover") || resName.contains("art") || resName.contains("entity")
        val isSheet = className.contains("bottomsheet") || resName.contains("sheet") || resName.contains("queue")
        val isCard = className.contains("card") || resName.contains("tile")
        val isSearchBar = resName == "browse_search_bar_container" || resName.contains("search")
        val isCat = resName == "seek_frame" || resName.contains("seek")

        val shouldRound = isImage || isCoverOrHeader || isSheet || isCard || isSearchBar || isCat

        if (shouldRound && !isAvatar) {
            view.clipToOutline = true
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + (if (isSheet) radiusLarge.toInt() else 0), radiusLarge)
                }
            }
        }
    }
}
