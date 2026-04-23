package io.github.chsbuffer.revancedxposed.spotify.misc

import app.revanced.extension.shared.Logger
import app.revanced.extension.spotify.misc.UnlockPremiumPatch
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.callMethod
import io.github.chsbuffer.revancedxposed.findField
import io.github.chsbuffer.revancedxposed.findFirstFieldByExactType
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Constructor
import java.lang.reflect.Field

@Suppress("UNCHECKED_CAST")
fun SpotifyHook.UnlockPremium() {

    // --- 1. SBLOCCO ATTRIBUTI (CORE PREMIUM) ---
    // Usiamo 'after' per intercettare il risultato.
    // Fondamentale: creiamo una copia, non modifichiamo l'oggetto originale.
    ::productStateProtoFingerprint.hookMethod {
        after { param ->
            val result = param.result as? Map<String, *> ?: return@after
            // Usiamo il metodo standard che probabilmente hai già
            UnlockPremiumPatch.overrideAttributes(result)
            // Se vuoi essere ultra-sicuro, non serve riassegnare param.result
            // perché la mappa è stata modificata internamente.
        }
    }

    // --- 2. POPULAR TRACKS (PAGINA ARTISTA) ---
    ::buildQueryParametersFingerprint.hookMethod {
        after { param ->
            val result = param.result ?: return@after
            val fieldName = "checkDeviceCapability"
            if (result.toString().contains("$fieldName=")) {
                param.result = XposedBridge.invokeOriginalMethod(
                    param.method, param.thisObject, arrayOf(param.args[0], true)
                )
            }
        }
    }

    // --- 3. GOOGLE ASSISTANT (FIX URIs) ---
    ::contextFromJsonFingerprint.hookMethod {
        fun safeRemoveStation(field: Field?, obj: Any?) {
            if (field == null || obj == null) return
            runCatching {
                val value = field.get(obj) as? String ?: return
                field.set(obj, UnlockPremiumPatch.removeStationString(value))
            }
        }

        after { param ->
            val result = param.result ?: return@after
            val clazz = result.javaClass
            safeRemoveStation(clazz.findField("uri"), result)
            safeRemoveStation(clazz.findField("url"), result)
        }
    }

    // --- 4. ANTI-SHUFFLE (GOOGLE ASSISTANT) ---
    runCatching {
        XposedHelpers.findAndHookMethod(
            $$"com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides$Builder",
            classLoader,
            "build",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.thisObject.callMethod("shufflingContext", false)
                }
            })
    }.onFailure { Logger.printDebug { "PlayerOptionOverrides hook fallito: ${it.message}" } }

    /*
    // --- 5. PULIZIA CONTEXT MENU (RIMUOVI ADS) ---
    val contextMenuViewModelClazz = ::contextMenuViewModelClass.clazz
    XposedBridge.hookAllConstructors(contextMenuViewModelClazz, object : XC_MethodHook() {
        val isPremiumUpsell = ::isPremiumUpsellField.field

        override fun beforeHookedMethod(param: MethodHookParam) {
            val parameterTypes = (param.method as Constructor<*>).parameterTypes
            for (i in param.args.indices) {
                if (parameterTypes[i].name != "java.util.List") continue
                val original = param.args[i] as? List<*> ?: continue

                // Filtriamo gli elementi che portano alla pubblicità Premium
                val filtered = original.filter { item ->
                    val vm = item?.callMethod("getViewModel")
                    vm?.let { isPremiumUpsell.get(it) as? Boolean } != true
                }
                param.args[i] = filtered
            }
        }
    })
    */

    // --- 6. RIMOZIONE SEZIONI ADS (HOME & BROWSE) ---
    // Per la Home
    ::homeStructureGetSectionsFingerprint.hookMethod {
        after { param ->
            val sections = param.result as? MutableList<*> ?: return@after
            runCatching {
                // Forza la lista a essere modificabile (evita l'errore di lista immutabile)
                sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)
                UnlockPremiumPatch.removeHomeSections(sections)
            }
        }
    }

    // Per il Browse
    ::browseStructureGetSectionsFingerprint.hookMethod {
        after { param ->
            val sections = param.result as? MutableList<*> ?: return@after
            runCatching {
                // Forza la lista a essere modificabile
                sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)
                UnlockPremiumPatch.removeBrowseSections(sections)
            }
        }
    }

    // --- 7. BLOCCO POPUP ADS (PENDRAGON) ---
    // Simula un errore di rete naturale invece di bloccare la chiamata
    val replaceWithRxError = object : XC_MethodHook() {
        val justMethod = DexMethod("Lio/reactivex/rxjava3/core/Single;->just(Ljava/lang/Object;)Lio/reactivex/rxjava3/core/Single;").toMethod()
        val onErrorField = DexField("Lio/reactivex/rxjava3/internal/operators/single/SingleOnErrorReturn;->b:Lio/reactivex/rxjava3/functions/Function;").toField()

        override fun afterHookedMethod(param: MethodHookParam) {
            if (!param.result.javaClass.name.endsWith("SingleOnErrorReturn")) return
            runCatching {
                val errorFunc = onErrorField.get(param.result)
                param.result = justMethod.invoke(null, errorFunc)
            }
        }
    }

    ::pendragonJsonFetchMessageRequestFingerprint.hookMethod(replaceWithRxError)
    ::pendragonJsonFetchMessageListRequestFingerprint.hookMethod(replaceWithRxError)
}
