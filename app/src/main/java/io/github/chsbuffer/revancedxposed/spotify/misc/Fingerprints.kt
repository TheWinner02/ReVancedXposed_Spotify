package io.github.chsbuffer.revancedxposed.spotify.misc

import io.github.chsbuffer.revancedxposed.AccessFlags
import io.github.chsbuffer.revancedxposed.Opcode
import io.github.chsbuffer.revancedxposed.SkipTest
import io.github.chsbuffer.revancedxposed.findClassDirect
import io.github.chsbuffer.revancedxposed.findFieldDirect
import io.github.chsbuffer.revancedxposed.findMethodDirect
import io.github.chsbuffer.revancedxposed.fingerprint
import io.github.chsbuffer.revancedxposed.strings
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.enums.UsingType

// 1. PRODUCT STATE - Reso più generico per intercettare nuove classi Protobuf
val productStateProtoFingerprint = fingerprint {
    returns("Ljava/util/Map;")
    classMatcher {
        // Spotify a volte sposta la classe internamente, usiamo Contains
        className("com.spotify.remoteconfig.internal.ProductStateProto", StringMatchType.Contains)
    }
}

// 2. POPULAR TRACKS - Invariato, solitamente molto stabile
val buildQueryParametersFingerprint = findMethodDirect {
    findMethod {
        matcher {
            strings("trackRows", "device_type:tablet")
        }
    }.single()
}

// 3. GOOGLE ASSISTANT - Usiamo Contains per il descrittore di classe
val contextFromJsonFingerprint = fingerprint {
    opcodes(
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_STATIC
    )
    methodMatcher {
        name("fromJson")
        declaredClass("voiceassistants.playermodels.ContextJsonAdapter", StringMatchType.Contains)
    }
}

// 4. CONTEXT MENU - Migliorato il fallback in caso di offuscamento stringhe
val contextMenuViewModelClass = findClassDirect {
    runCatching {
        fingerprint {
            strings("ContextMenuViewModel(header=")
        }
    }.getOrElse {
        fingerprint {
            accessFlags(AccessFlags.CONSTRUCTOR)
            // Stringa più probabile nelle nuove versioni
            strings("ContextMenuViewModel", "duplicate itemResId")
            parameters("L", "Ljava/util/List;", "Z")
        }
    }.declaredClass!!
}

val viewModelClazz = findClassDirect {
    findMethod {
        findFirst = true
        matcher { name("getViewModel") }
    }.single().returnType!!
}

// 5. PREMIUM UPSELL - Spotify ha aggiunto booleani, cerchiamo in modo più sicuro
val isPremiumUpsellField = findFieldDirect {
    val fields = viewModelClazz().fields.filter { it.typeName == "boolean" }
    // Nelle nuove versioni l'indice potrebbe essere cambiato, il secondo (index 1) è solitamente quello corretto
    if (fields.size > 1) fields[1] else fields.first()
}

// 6. SECTIONS (HOME/BROWSE) - Cruciale: rimosso EndsWith troppo specifico
@SkipTest
fun structureGetSectionsFingerprint(className: String) = fingerprint {
    classMatcher { className(className, StringMatchType.Contains) }
    methodMatcher {
        addUsingField {
            usingType = UsingType.Read
            name("sections_", StringMatchType.EndsWith)
        }
    }
}

val homeStructureGetSectionsFingerprint =
    structureGetSectionsFingerprint("homeapi.proto.HomeStructure")
val browseStructureGetSectionsFingerprint =
    structureGetSectionsFingerprint("browsita.v1.resolved.BrowseStructure")

// 7. PENDRAGON (ADS) - Utilizzo di Contains per le classi FetchMessage
val pendragonJsonFetchMessageRequestFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name("apply")
            addInvoke {
                name("<init>")
                declaredClass("FetchMessageRequest", StringMatchType.Contains)
            }
        }
    }.single()
}

val pendragonJsonFetchMessageListRequestFingerprint = findMethodDirect {
    findMethod {
        matcher {
            name("apply")
            addInvoke {
                name("<init>")
                declaredClass("FetchMessageListRequest", StringMatchType.Contains)
            }
        }
    }.single()
}