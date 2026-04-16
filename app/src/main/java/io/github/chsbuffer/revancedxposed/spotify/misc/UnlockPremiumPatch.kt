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

    // --- 1. SBLOCCO ATTRIBUTI (CORE PREMIUM & FIX 14 GIORNI) ---
    runCatching {
        ::productStateProtoFingerprint.hookMethod {
            after { param ->
                val result = param.result as? Map<String, Any> ?: return@after

                // Proviamo il patch standard
                UnlockPremiumPatch.overrideAttributes(result)

                // FORZATURA MANUALE (Anti-14 giorni / Anti-Free)
                // Se la mappa è mutabile (quasi sempre in questa fase), forziamo i valori chiave
                (result as? MutableMap<String, Any>)?.apply {
                    put("type", "premium")
                    put("product", "premium")
                    put("can_download", "true")
                    put("ads", "0")
                    put("streaming-rules", "") // Rimuove restrizioni geografiche
                }

                Logger.printDebug { "UnlockPremium: Attributi patchati (Stato: ${result["type"]})" }
            }
        }
    }.onFailure { Logger.printDebug { "ERRORE CRITICO: Fingerprint ProductState non trovato!" } }

    // --- 2. POPULAR TRACKS (PAGINA ARTISTA) ---
    runCatching {
        ::buildQueryParametersFingerprint.hookMethod {
            after { param ->
                val result = param.result ?: return@after
                if (result.toString().contains("checkDeviceCapability=")) {
                    param.result = XposedBridge.invokeOriginalMethod(
                        param.method, param.thisObject, arrayOf(param.args[0], true)
                    )
                }
            }
        }
    }

    // --- 3. GOOGLE ASSISTANT (URIs) ---
    runCatching {
        ::contextFromJsonFingerprint.hookMethod {
            fun safeRemoveStation(field: Field?, obj: Any?) {
                if (field == null || obj == null) return
                runCatching {
                    val value = field.get(obj) as? String ?: return
                    field.set(obj, UnlockPremiumPatch.removeStationString(value))
                }
            }
            after { param ->
                val res = param.result ?: return@after
                safeRemoveStation(res.javaClass.findField("uri"), res)
                safeRemoveStation(res.javaClass.findField("url"), res)
            }
        }
    }

    // --- 4. ANTI-SHUFFLE ---
    runCatching {
        XposedHelpers.findAndHookMethod(
            "com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides\$Builder",
            classLoader, "build",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.thisObject.callMethod("shufflingContext", false)
                }
            }
        )
    }

    // --- 5. PULIZIA CONTEXT MENU (ANTI-CRASH) ---
    runCatching {
        val menuClazz = ::contextMenuViewModelClass.clazz
        XposedBridge.hookAllConstructors(menuClazz, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val isPremiumUpsell = ::isPremiumUpsellField.field
                val parameterTypes = (param.method as Constructor<*>).parameterTypes
                for (i in param.args.indices) {
                    if (parameterTypes[i].name != "java.util.List") continue
                    val original = param.args[i] as? List<*> ?: continue

                    // Creiamo una NUOVA lista filtrata per evitare UnsupportedOperationException (liste immutabili)
                    param.args[i] = original.filter { item ->
                        runCatching {
                            val vm = item?.callMethod("getViewModel")
                            vm?.let { isPremiumUpsell.get(it) as? Boolean } != true
                        }.getOrDefault(true)
                    }
                }
            }
        })
    }

    // --- 6. RIMOZIONE SEZIONI ADS (HOME & BROWSE) ---
    fun safePatchSections(param: XC_MethodHook.MethodHookParam, isHome: Boolean) {
        val list = param.result as? List<*> ?: return
        runCatching {
            // Nelle nuove versioni non cerchiamo più il campo Boolean 'isMutable'.
            // Creiamo invece una copia mutabile, la patchiamo e la riassegniamo.
            val mutableList = list.toMutableList()
            if (isHome) UnlockPremiumPatch.removeHomeSections(mutableList)
            else UnlockPremiumPatch.removeBrowseSections(mutableList)
            param.result = mutableList
        }.onFailure { Logger.printDebug { "Sezioni ${if(isHome) "Home" else "Browse"} fallite: ${it.message}" } }
    }

    runCatching {
        ::homeStructureGetSectionsFingerprint.hookMethod { after { safePatchSections(it, true) } }
        ::browseStructureGetSectionsFingerprint.hookMethod { after { safePatchSections(it, false) } }
    }

    // --- 7. BLOCCO POPUP ADS (PENDRAGON) ---
    runCatching {
        val replaceWithRxError = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val res = param.result ?: return
                if (!res.javaClass.name.endsWith("SingleOnErrorReturn")) return
                runCatching {
                    val justMethod = DexMethod("Lio/reactivex/rxjava3/core/Single;->just(Ljava/lang/Object;)Lio/reactivex/rxjava3/core/Single;").toMethod()
                    val onErrorField = DexField("Lio/reactivex/rxjava3/internal/operators/single/SingleOnErrorReturn;->b:Lio/reactivex/rxjava3/functions/Function;").toField()
                    param.result = justMethod.invoke(null, onErrorField.get(res))
                }
            }
        }
        ::pendragonJsonFetchMessageRequestFingerprint.hookMethod(replaceWithRxError)
        ::pendragonJsonFetchMessageListRequestFingerprint.hookMethod(replaceWithRxError)
    }
}