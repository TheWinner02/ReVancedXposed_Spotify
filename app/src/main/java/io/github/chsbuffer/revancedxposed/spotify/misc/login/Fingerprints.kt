package io.github.chsbuffer.revancedxposed.spotify.misc.login

import io.github.chsbuffer.revancedxposed.AccessFlags
import io.github.chsbuffer.revancedxposed.FindMethodFunc
import io.github.chsbuffer.revancedxposed.Opcode
import io.github.chsbuffer.revancedxposed.fingerprint
import io.github.chsbuffer.revancedxposed.parameters
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

object Fingerprints {

    // 1. CLIENT ID
    val setClientIdFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            name = "setClientId"
            parameters("Ljava/lang/String;")
        }
    }

    // 2. USER AGENT
    val setUserAgentFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            name = "setDefaultHTTPUserAgent"
            parameters("Ljava/lang/String;")
        }
    }

    // 3. ACCESS POINT
    val setAccessPointFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            parameters("Ljava/lang/String;")
            // Cerchiamo il metodo che viene usato per configurare l'endpoint
            usingStrings("accesspoint", "https://")
        }
    }

    // 4. PLATFORM GETTER (Singolo o Lista)
    val loginClientPlatformFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            returnType = "java.lang.String"
            usingStrings("android")
            parameters()
            accessFlags(AccessFlags.PUBLIC)
        }
    }

    // 5. INTEGRITY BYPASS
    val runIntegrityVerificationFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
            returns("V")
            opcodes(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.IF_EQ)
        }
    }

    // 6. ORBIT LIBRARY LOAD
    // Basato su grep: trovato in p/a54.smali [cite: 1]
    val orbitLibraryFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            usingStrings("liborbit-jni-spotify.so")
        }
    }

    // 7. DEEP SPOOF (Mappe) - Più specifico per velocizzare
    val loginMapFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            parameters("Ljava/util/Map;")
            accessFlags(AccessFlags.PUBLIC)
            // Cerchiamo metodi che NON restituiscono nulla (void)
            // perché solitamente "riempiono" la mappa passata come argomento
            returnType = "V"
        }
    }

    // 8. METODI GENERICI (Versioni/Hardware)
    fun findClientDataMethods(bridge: DexKitBridge, type: String): List<MethodData> {
        return bridge.findMethod {
            matcher {
                returnType = "java.lang.String"
                parameters()
                modifiers = Modifier.PUBLIC
                when(type) {
                    "getClientVersion" -> usingStrings("android/")
                    "getSystemVersion" -> usingStrings("release")
                    "getHardwareMachine" -> usingStrings("model")
                }
            }
        }
    }

    // 9.Fingerprint per il Protobuf di Login (ClientInfo)
    val clientInfoFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            // Cerchiamo qualsiasi metodo che contenga la stringa "android" e la cambi in "ios"
            // o che sia chiaramente un setter di piattaforma
            usingStrings("android")
            name = "setPlatform"
        }
    }

    // 10.Fingerprint per DeviceInformation (Dati sessione)
    val deviceInfoFingerprint: FindMethodFunc = fingerprint {
        classMatcher { descriptor = "Lcom/spotify/pses/v1/proto/DeviceInformation;" }
        methodMatcher {
            name = "setOsVersion"
            parameters("Ljava/lang/String;")
        }
    }
}