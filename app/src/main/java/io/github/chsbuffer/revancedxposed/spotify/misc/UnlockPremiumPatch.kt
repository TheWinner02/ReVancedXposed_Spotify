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
    ::productStateProtoFingerprint.hookMethod {
        after { param ->
            val result = param.getResult() as? Map<String, *> ?: return@after
            UnlockPremiumPatch.overrideAttributes(result)
        }
    }

    // --- 2. POPULAR TRACKS (PAGINA ARTISTA) ---
    ::buildQueryParametersFingerprint.hookMethod {
        after { param ->
            val result = param.getResult() ?: return@after
            val fieldName = "checkDeviceCapability"
            if (result.toString().contains("$fieldName=")) {
                val method = param.method as? java.lang.reflect.Method ?: return@after
                param.setResult(method.invoke(param.thisObject, param.args?.get(0), true))
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
            val result = param.getResult() ?: return@after
            val clazz = result.javaClass
            safeRemoveStation(clazz.findField("uri"), result)
            safeRemoveStation(clazz.findField("url"), result)
        }
    }

    // --- 4. ANTI-SHUFFLE (GOOGLE ASSISTANT) ---
    runCatching {
        val builderClassName = "com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides\$Builder"
        val builderClass = Class.forName(builderClassName, false, classLoader)
        XposedHelpers.findAndHookMethod(
            builderClass,
            "build",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.thisObject?.callMethod("shufflingContext", false)
                }
            })
    }.onFailure { Logger.printDebug { "PlayerOptionOverrides hook fallito: ${it.message}" } }

    // --- 5. PULIZIA CONTEXT MENU (RIMUOVI ADS) ---
    runCatching {
        val contextMenuViewModelClazz = ::contextMenuViewModelClass.clazz
        contextMenuViewModelClazz.declaredConstructors.forEach { ctor ->
            XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                val isPremiumUpsell = runCatching { ::isPremiumUpsellField.field }.getOrNull()

                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isPremiumUpsell == null) return
                    val args = param.args ?: return
                    val parameterTypes = (param.method as Constructor<*>).parameterTypes
                    for (i in args.indices) {
                        if (parameterTypes[i].name != "java.util.List") continue
                        val original = args[i] as? List<*> ?: continue

                        val filtered = original.filter { item ->
                            val vm = item?.callMethod("getViewModel")
                            vm?.let { isPremiumUpsell.get(it) as? Boolean } != true
                        }
                        args[i] = filtered
                    }
                }
            })
        }
    }.onFailure { Logger.printDebug { "ContextMenu hook fallito: ${it.message}" } }

    // --- 6. RIMOZIONE SEZIONI ADS (HOME & BROWSE) ---
    ::homeStructureGetSectionsFingerprint.hookMethod {
        after { param ->
            val sections = param.getResult() as? MutableList<*> ?: return@after
            runCatching {
                sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)
                UnlockPremiumPatch.removeHomeSections(sections)
            }
        }
    }

    ::browseStructureGetSectionsFingerprint.hookMethod {
        after { param ->
            val sections = param.getResult() as? MutableList<*> ?: return@after
            runCatching {
                sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)
                UnlockPremiumPatch.removeBrowseSections(sections)
            }
        }
    }

    // --- 7. BLOCCO POPUP ADS (PENDRAGON) ---
    val replaceWithRxError = object : XC_MethodHook() {
        val justMethod = DexMethod("Lio/reactivex/rxjava3/core/Single;->just(Ljava/lang/Object;)Lio/reactivex/rxjava3/core/Single;").toMethod()
        val onErrorField = DexField("Lio/reactivex/rxjava3/internal/operators/single/SingleOnErrorReturn;->b:Lio/reactivex/rxjava3/functions/Function;").toField()

        override fun afterHookedMethod(param: MethodHookParam) {
            val result = param.getResult() ?: return
            if (!result.javaClass.name.endsWith("SingleOnErrorReturn")) return
            runCatching {
                val errorFunc = onErrorField.get(result)
                param.setResult(justMethod.invoke(null, errorFunc))
            }
        }
    }

    ::pendragonJsonFetchMessageRequestFingerprint.hookMethod(replaceWithRxError)
    ::pendragonJsonFetchMessageListRequestFingerprint.hookMethod(replaceWithRxError)
}
