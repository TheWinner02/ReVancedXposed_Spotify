package io.github.chsbuffer.revancedxposed.spotify

import android.content.res.Resources
import android.graphics.Outline
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class RoundyUIHook(private val lpparam: XC_LoadPackage.LoadPackageParam) {

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            Resources.getSystem().displayMetrics
        )
    }

    private val radiusLarge = dpToPx(28f)
    private val radiusFull = dpToPx(100f)

    // Keywords estese per catturare tutto ciò che è una "piastrella" o "copertina"
    private val targetKeywords = listOf(
        "album_art", "image", "card", "entity_image", "content_image", "entity", "content", "category",
        "browse", "search", "query", "background", "tile", "row_icon", "thumb"
    )

    fun hook() {
        val classLoader = lpparam.classLoader

        // 1. Hook UNIVERSALE per lo stondamento basato su ID e Classe
        XposedHelpers.findAndHookMethod(
            "android.view.View",
            classLoader,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                        applyRoundingIfTarget(view)
                }
            }
        )

        // 2. Hook specifico per le ImageView (Copertine Playlist)
        // Spesso onAttachedToWindow non basta se l'immagine viene riciclata in una lista
        XposedHelpers.findAndHookMethod(
            "android.widget.ImageView",
            classLoader,
            "setImageDrawable",
            android.graphics.drawable.Drawable::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val imageView = param.thisObject as ImageView
                        applyRoundingIfTarget(imageView)
                }
            }
        )

        // 3. Hook per i GradientDrawable (Pulsanti)
        XposedHelpers.findAndHookMethod(
            "android.graphics.drawable.GradientDrawable",
            classLoader,
            "setCornerRadius",
            Float::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val original = param.args[0] as Float
                    param.args[0] = if (original > dpToPx(15f)) radiusFull else radiusLarge
                }
            }
        )

        // 4. Hook per i BottomSheets (Il contenitore che scivola dal basso)
        XposedHelpers.findAndHookMethod(
            "com.google.android.material.bottomsheet.BottomSheetBehavior",
            classLoader,
            "onLayoutChild",
            "androidx.coordinatorlayout.widget.CoordinatorLayout",
            View::class.java,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.args[1] as View

                    // Applichiamo lo stondamento solo agli angoli SUPERIORI (Top Left e Top Right)
                    // Tipico dei BottomSheet Material 3
                    view.clipToOutline = true
                    view.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            // Creiamo un rettangolo che esce dal basso per non stondare gli angoli inferiori
                            outline.setRoundRect(
                                0, 0,
                                view.width, view.height + radiusLarge.toInt(),
                                radiusLarge
                            )
                        }
                    }
                }
            }
        )

// 5. Hook di rinforzo per i Background dei BottomSheet
// Molte app usano un MaterialShapeDrawable per gestire gli angoli dei pannelli
        XposedHelpers.findAndHookMethod(
            "com.google.android.material.shape.MaterialShapeDrawable",
            classLoader,
            "setInterpolation",
            Float::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val drawable = param.thisObject as android.graphics.drawable.Drawable
                    // Se il raggio è impostato via codice, lo forziamo al nostro radiusLarge
                    XposedHelpers.callMethod(param.thisObject, "setCornerSize", radiusLarge)
                }
            }
        )
    }

    private fun applyRoundingIfTarget(view: View) {
        val resName = try { view.resources.getResourceEntryName(view.id) } catch (_: Exception) { "" }
        val className = view.javaClass.name.lowercase()

        // 1. RICONOSCIMENTO IMMAGINI (Copertine Brani e Playlist)
        // Usiamo il controllo sulla classe ImageView per non mancare nulla
        val isImage = view is android.widget.ImageView || className.contains("imageview")

        // 2. RICONOSCIMENTO HEADER E COVER (La copertina grande in alto)
        val isCoverOrHeader = resName.contains("header") ||
                resName.contains("cover") ||
                resName.contains("art") ||
                resName.contains("entity")

        // 3. RICONOSCIMENTO SHEET E CARD
        val isSheet = className.contains("bottomsheet") || resName.contains("sheet") || resName.contains("queue")
        val isCard = className.contains("card") || resName.contains("tile")

        // 4. FILTRO RIGHE (Per evitare di tagliare il testo nella libreria/playlist)
        // Se è un contenitore (Layout) ma NON è un'immagine e NON è uno sheet
        val isTextContainerRow = (resName.contains("row") || resName.contains("item")) && !isImage && !isSheet

        // LOGICA DI SELEZIONE
        val shouldRound = when {
            isImage -> true           // Tutte le foto (piccole e grandi)
            isCoverOrHeader -> true   // Copertina principale Radio/Playlist
            isSheet -> true           // Menu e Coda
            isCard -> true            // Card Home/Search
            resName.contains("search") || resName.contains("browse") -> true
            else -> false
        }

        if (shouldRound) {
            // Se è il contenitore della riga (quello che contiene testo), NON clippiamo
            if (isTextContainerRow) {
                view.clipToOutline = false
                return
            }

            view.clipToOutline = true
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (isSheet) {
                        // Solo angoli superiori per i pannelli
                        outline.setRoundRect(0, 0, view.width, view.height + radiusLarge.toInt(), radiusLarge)
                    } else {
                        // Stondamento completo per tutte le copertine (brani e header)
                        outline.setRoundRect(0, 0, view.width, view.height, radiusLarge)
                    }
                }
            }
        }
    }
}