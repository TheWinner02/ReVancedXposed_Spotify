package io.github.chsbuffer.revancedxposed.spotify.misc.login

import io.github.chsbuffer.revancedxposed.AccessFlags
import io.github.chsbuffer.revancedxposed.FindMethodFunc
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

    // 3. MAPPA DI LOGIN
    // Usiamo una stringa specifica per aiutarlo a essere più unico,
    // ma manteniamo la struttura che piace al tuo compilatore.
    val loginMapFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            parameters("Ljava/util/Map;")
            returnType = "V"
            // Aggiungiamo i modificatori per restringere il campo dai 12 risultati
            accessFlags(AccessFlags.PUBLIC)
        }
    }

    // 4. PROTOBUF CLIENT INFO
    val clientInfoFingerprint: FindMethodFunc = fingerprint {
        classMatcher { descriptor = "Lcom/spotify/signup/signup/v2/proto/ClientInfo;" }
        methodMatcher {
            parameters("Ljava/lang/String;")
        }
    }

    // 5. DEVICE INFORMATION
    val deviceInfoFingerprint: FindMethodFunc = fingerprint {
        classMatcher { descriptor = "Lcom/spotify/pses/v1/proto/DeviceInformation;" }
        methodMatcher {
            parameters("Ljava/lang/String;")
        }
    }

    // 6. METODI HARDWARE DINAMICI
    fun findClientDataMethods(bridge: DexKitBridge, type: String): List<MethodData> {
        return bridge.findMethod {
            matcher {
                returnType = "java.lang.String"
                parameters()
                modifiers = Modifier.PUBLIC
                when(type) {
                    "getHardwareMachine" -> usingStrings("model")
                    "getSystemVersion" -> usingStrings("release")
                }
            }
        }
    }
}