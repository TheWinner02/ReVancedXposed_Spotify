package io.github.chsbuffer.revancedxposed.spotify.misc

import app.revanced.extension.shared.Logger
import app.revanced.extension.spotify.misc.UnlockPremiumPatch
import io.github.chsbuffer.revancedxposed.*
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import java.lang.reflect.Field

@Suppress("UNCHECKED_CAST")
fun SpotifyHook.UnlockPremium() {

    // --- 1. SBLOCCO ATTRIBUTI (CORE PREMIUM) ---
    ::productStateProtoFingerprint.hookMethod(object : ChimeraBridge.XC_MethodHook() {
        override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
            val result = param.result as? Map<String, *> ?: return
            UnlockPremiumPatch.overrideAttributes(result)
        }
    })

    // --- 2. POPULAR TRACKS (PAGINA ARTISTA) ---
    ::buildQueryParametersFingerprint.hookMethod(object : ChimeraBridge.XC_MethodHook() {
        override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
            val result = param.result ?: return
            val fieldName = "checkDeviceCapability"
            if (result.toString().contains("$fieldName=")) {
                // Placeholder for invokeOriginalMethod
            }
        }
    })

    // --- 3. GOOGLE ASSISTANT (FIX URIs) ---
    ::contextFromJsonFingerprint.hookMethod(object : ChimeraBridge.XC_MethodHook() {
        fun safeRemoveStation(field: Field?, obj: Any?) {
            if (field == null || obj == null) return
            runCatching {
                field.isAccessible = true
                val value = field.get(obj) as? String ?: return
                field.set(obj, UnlockPremiumPatch.removeStationString(value))
            }
        }

        override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
            val result = param.result ?: return
            val clazz = result.javaClass
            safeRemoveStation(clazz.getDeclaredFieldRecursive("uri"), result)
            safeRemoveStation(clazz.getDeclaredFieldRecursive("url"), result)
        }
    })

    // --- 4. ANTI-SHUFFLE (GOOGLE ASSISTANT) ---
    runCatching {
        val builderClass = "com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides\$Builder".findClass(classLoader)
        val buildMethod = builderClass.getDeclaredMethodRecursive("build")
        
        ChimeraBridge.hookMethod(buildMethod, object : ChimeraBridge.XC_MethodHook() {
                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    param.thisObject?.callMethod("shufflingContext", false)
                }
            })
    }.onFailure { Logger.printDebug { "PlayerOptionOverrides hook fallito: ${it.message}" } }

    // --- 5. PULIZIA CONTEXT MENU (RIMUOVI ADS) ---
    runCatching {
        val contextMenuViewModelClazz = ::contextMenuViewModelClass.clazz
        val constructors = contextMenuViewModelClazz.declaredConstructors
        if (constructors.isNotEmpty()) {
            ChimeraBridge.hookMethod(constructors[0], object : ChimeraBridge.XC_MethodHook() {
                val isPremiumUpsell = runCatching { ::isPremiumUpsellField.field }.getOrNull()

                override fun beforeHookedMethod(param: ChimeraBridge.MethodHookParam) {
                    if (isPremiumUpsell == null) return
                    val original = param.args?.get(0) as? List<*> ?: return
                    val filtered = original.filter { item ->
                        val vm = item?.callMethod("getViewModel")
                        vm?.let { 
                            isPremiumUpsell.isAccessible = true
                            isPremiumUpsell.get(it) as? Boolean 
                        } != true
                    }
                    param.args?.set(0, filtered)
                }
            })
        }
    }.onFailure { Logger.printDebug { "ContextMenu hook fallito: ${it.message}" } }

    // --- 6. RIMOZIONE SEZIONI ADS (HOME & BROWSE) ---
    ::homeStructureGetSectionsFingerprint.hookMethod(object : ChimeraBridge.XC_MethodHook() {
        override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
            val sections = param.result as? MutableList<*> ?: return
            runCatching {
                val field = sections.javaClass.findFirstFieldByExactType(Boolean::class.java)
                field.isAccessible = true
                field.set(sections, true)
                UnlockPremiumPatch.removeHomeSections(sections)
            }
        }
    })

    ::browseStructureGetSectionsFingerprint.hookMethod(object : ChimeraBridge.XC_MethodHook() {
        override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
            val sections = param.result as? MutableList<*> ?: return
            runCatching {
                val field = sections.javaClass.findFirstFieldByExactType(Boolean::class.java)
                field.isAccessible = true
                field.set(sections, true)
                UnlockPremiumPatch.removeBrowseSections(sections)
            }
        }
    })

    // --- 7. BLOCCO POPUP ADS (PENDRAGON) ---
    val replaceWithRxError = object : ChimeraBridge.XC_MethodHook() {
        override fun afterHookedMethod(param: ChimeraBridge.MethodHookParam) {
            val result = param.result ?: return
            if (!result.javaClass.name.endsWith("SingleOnErrorReturn")) return
            runCatching {
                val onErrorField = result.javaClass.getDeclaredField("b")
                onErrorField.isAccessible = true
                val errorFunc = onErrorField.get(result)
                val singleClass = Class.forName("io.reactivex.rxjava3.core.Single", false, result.javaClass.classLoader)
                val justMethod = singleClass.getDeclaredMethod("just", Any::class.java)
                param.setResult(justMethod.invoke(null, errorFunc))
            }
        }
    }

    ::pendragonJsonFetchMessageRequestFingerprint.hookMethod(replaceWithRxError)
    ::pendragonJsonFetchMessageListRequestFingerprint.hookMethod(replaceWithRxError)
}
