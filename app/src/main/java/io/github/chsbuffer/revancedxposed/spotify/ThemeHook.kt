package io.github.chsbuffer.revancedxposed.spotify

import android.app.Application
import android.graphics.Color
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class ThemeHook(private val app: Application, private val lpparam: XC_LoadPackage.LoadPackageParam) {

    private val colorCache = HashMap<Int, Int>()
    private val res = app.resources

    // --- COLORI MONET ---
    private val primaryBg = try {
        app.getColor(res.getIdentifier("system_neutral1_900", "color", "android"))
    } catch (e: Exception) { Color.BLACK }

    private val secondaryBg = try {
        app.getColor(res.getIdentifier("system_neutral1_800", "color", "android"))
    } catch (e: Exception) { Color.parseColor("#121212") }

    private val accent = try {
        app.getColor(res.getIdentifier("system_accent1_200", "color", "android"))
    } catch (e: Exception) { Color.parseColor("#1DB954") }

    // NUOVO: Valore per lo stato "Pressed"
    private val accentPressed = try {
        app.getColor(res.getIdentifier("system_accent1_400", "color", "android"))
    } catch (e: Exception) { Color.parseColor("#1ABC54") }

    fun hook() {
        val classLoader = lpparam.classLoader

        // 1. PorterDuffColorFilter (Rimane invariato, va bene così)
        XposedHelpers.findAndHookConstructor(
            "android.graphics.PorterDuffColorFilter",
            classLoader,
            Int::class.javaPrimitiveType,
            android.graphics.PorterDuff.Mode::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = replaceColorLogic(param.args[0] as Int)
                }
            }
        )

        // 2. ColorStateList: Gestione chirurgica degli stati (Pressed/Selected)
        XposedHelpers.findAndHookMethod(
            "android.content.res.ColorStateList",
            classLoader,
            "getColorForState",
            IntArray::class.java,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val states = param.args[0] as IntArray
                    val originalColor = param.result as Int

                    val isPressed = states.contains(android.R.attr.state_pressed)
                    val isSelected = states.contains(android.R.attr.state_selected)
                    val isFocused = states.contains(android.R.attr.state_focused)

                    param.result = when {
                        // SE PREMUTO: Applichiamo il colore accentPressed con un'opacità del 30%
                        // Questo crea l'effetto "vetro colorato" sopra le copertine
                        isPressed || isFocused -> {
                            Color.argb(
                                77, // Alpha fisso al 30% (circa 77/255)
                                Color.red(accentPressed),
                                Color.green(accentPressed),
                                Color.blue(accentPressed)
                            )
                        }

                        // SE SELEZIONATO (es. icona NavBar attiva): Accent pieno
                        isSelected -> accent

                        // ALTRIMENTI: Logica standard
                        else -> replaceColorLogic(originalColor)
                    }
                }
            }
        )

        // 3. Color.parseColor
        XposedHelpers.findAndHookMethod(
            "android.graphics.Color",
            classLoader,
            "parseColor",
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = replaceColorLogic(param.result as Int)
                }
            }
        )

        // 4. Paint.setColor
        XposedHelpers.findAndHookMethod(
            "android.graphics.Paint",
            classLoader,
            "setColor",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = replaceColorLogic(param.args[0] as Int)
                }
            }
        )

        // 5. GradientDrawable
        XposedHelpers.findAndHookMethod(
            "android.graphics.drawable.GradientDrawable",
            classLoader,
            "setColors",
            IntArray::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val colors = param.args[0] as IntArray
                    for (i in colors.indices) colors[i] = replaceColorLogic(colors[i])
                }
            }
        )
        // 6. Hook alle Risorse (getColor)
// Intercetta ogni volta che Spotify chiede un colore tramite ID (es. R.color.spotify_green)
        XposedHelpers.findAndHookMethod(
            "android.content.res.Resources",
            lpparam.classLoader,
            "getColor",
            Int::class.javaPrimitiveType,
            "android.content.res.Resources.Theme",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val color = param.result as Int
                    param.result = replaceColorLogic(color)
                }
            }
        )

// 7. Hook alle Risorse (getColorStateList)
// Fondamentale per switch e icone nelle impostazioni
        XposedHelpers.findAndHookMethod(
            "android.content.res.Resources",
            lpparam.classLoader,
            "getColorStateList",
            Int::class.javaPrimitiveType,
            "android.content.res.Resources.Theme",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val csl = param.result as? android.content.res.ColorStateList ?: return

                    // Creiamo una nuova ColorStateList basata sulla nostra logica Monet
                    // Questo forzerà switch e testi cliccabili a seguire il tema
                    param.result = android.content.res.ColorStateList.valueOf(replaceColorLogic(csl.defaultColor))
                }
            }
        )

// 8. Hook ai TypedArray (Il "colpo finale" per gli XML)
// Quando Android legge un attributo da un tema XML (es. ?attr/colorPrimary)
        XposedHelpers.findAndHookMethod(
            "android.content.res.TypedArray",
            lpparam.classLoader,
            "getColor",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val color = param.result as Int
                    param.result = replaceColorLogic(color)
                }
            }
        )
    }

    private fun replaceColorLogic(color: Int): Int {
        if (color == Color.TRANSPARENT) return color
        colorCache[color]?.let { return it }

        val a = Color.alpha(color)
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        val newColor = when {
            // VERDE SPOTIFY -> ACCENT
            (g > 100 && g > r && g > b) -> accent

            // ICONE E TESTI LUMINOSI -> ACCENT
            (r > 150 && Math.abs(r - g) < 20 && Math.abs(r - b) < 20) -> accent

            // SFONDI NERI -> PRIMARY BG
            (r < 45 && g < 45 && b < 45) -> primaryBg

            // GRIGI MEDI (Testi secondari, icone inattive) -> Spesso meglio lasciarli originali o un accent molto scuro
            (r in 46..120 && g in 46..120 && b in 46..120) -> secondaryBg

            else -> color
        }

        // Ricostruiamo preservando l'alpha (tranne per lo stato pressed che gestiamo sopra)
        val finalColor = Color.argb(a, Color.red(newColor), Color.green(newColor), Color.blue(newColor))
        colorCache[color] = finalColor
        return finalColor
    }
}