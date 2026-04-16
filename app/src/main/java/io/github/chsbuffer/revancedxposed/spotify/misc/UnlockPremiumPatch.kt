package io.github.chsbuffer.revancedxposed.spotify.misc

import app.revanced.extension.shared.Logger
import app.revanced.extension.spotify.misc.UnlockPremiumPatch
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.chsbuffer.revancedxposed.callMethod
import io.github.chsbuffer.revancedxposed.findFirstFieldByExactType
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

@Suppress("UNCHECKED_CAST")
fun SpotifyHook.UnlockPremium() {

    // --- 1. SBLOCCO ATTRIBUTI ---
    runCatching {
        ::productStateProtoFingerprint.hookMethod {
            after { param ->
                val result = param.result as? Map<String, *> ?: return@after
                UnlockPremiumPatch.overrideAttributes(result)
            }
        }
    }.onFailure { Logger.printDebug { "Unlock: Attributi non trovati" } }

    // --- 2. POPULAR TRACKS ---
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

    // --- 3. CONTEXT MENU (FILTRO ANTI-ADS) ---
    runCatching {
        val menuClazz = ::contextMenuViewModelClass.clazz
        XposedBridge.hookAllConstructors(menuClazz, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val isPremiumUpsell = ::isPremiumUpsellField.field
                val args = param.args
                for (i in args.indices) {
                    val list = args[i] as? List<*> ?: continue
                    if (list.isEmpty()) continue

                    // Nelle nuove versioni le liste sono immutabili.
                    // Il filter di Kotlin crea una NUOVA lista, risolvendo il problema.
                    val filtered = list.filter { item ->
                        runCatching {
                            val vm = item?.callMethod("getViewModel")
                            vm?.let { isPremiumUpsell.get(it) as? Boolean } != true
                        }.getOrDefault(true)
                    }
                    args[i] = filtered
                }
            }
        })
    }

    // --- 4. RIMOZIONE SEZIONI ADS (HOME & BROWSE) ---
    // Gestione sicura per le nuove liste di Spotify 9.1.40
    fun patchSections(param: XC_MethodHook.MethodHookParam, type: String) {
        val list = param.result as? List<*> ?: return
        runCatching {
            // Proviamo a rendere la lista mutabile solo se necessario
            runCatching {
                list.javaClass.findFirstFieldByExactType(Boolean::class.java).set(list, true)
            }
            if (type == "home") UnlockPremiumPatch.removeHomeSections(list as MutableList<*>)
            else UnlockPremiumPatch.removeBrowseSections(list as MutableList<*>)
        }.onFailure { Logger.printDebug { "Fallito patch sezioni $type" } }
    }

    runCatching {
        ::homeStructureGetSectionsFingerprint.hookMethod {
            after { patchSections(it, "home") }
        }
    }
    runCatching {
        ::browseStructureGetSectionsFingerprint.hookMethod {
            after { patchSections(it, "browse") }
        }
    }

    // --- 5. BLOCCO POPUP (PENDRAGON) ---
    runCatching {
        val replaceWithRxError = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.result ?: return
                if (!result.javaClass.name.endsWith("SingleOnErrorReturn")) return
                runCatching {
                    val justMethod = DexMethod("Lio/reactivex/rxjava3/core/Single;->just(Ljava/lang/Object;)Lio/reactivex/rxjava3/core/Single;").toMethod()
                    val onErrorField = DexField("Lio/reactivex/rxjava3/internal/operators/single/SingleOnErrorReturn;->b:Lio/reactivex/rxjava3/functions/Function;").toField()
                    param.result = justMethod.invoke(null, onErrorField.get(result))
                }
            }
        }
        ::pendragonJsonFetchMessageRequestFingerprint.hookMethod(replaceWithRxError)
        ::pendragonJsonFetchMessageListRequestFingerprint.hookMethod(replaceWithRxError)
    }
}