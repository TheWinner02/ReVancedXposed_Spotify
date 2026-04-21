package io.github.chsbuffer.revancedxposed.spotify.misc.login

import io.github.chsbuffer.revancedxposed.AccessFlags
import io.github.chsbuffer.revancedxposed.FindMethodFunc
import io.github.chsbuffer.revancedxposed.fingerprint
import io.github.chsbuffer.revancedxposed.parameters
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

object Fingerprints {

    // 1. CLIENT ID (Quello che mancava)
    // Cerca il metodo setter che imposta l'ID del client (fondamentale per il login iOS)
    val setClientIdFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            name = "setClientId"
            parameters("Ljava/lang/String;")
        }
    }

    // 2. USER AGENT (Backup)
    val setUserAgentFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            name = "setDefaultHTTPUserAgent"
            parameters("Ljava/lang/String;")
        }
    }

    // 3. MAPPA DI LOGIN (Il "tuttofare")
    val loginMapFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            parameters("Ljava/util/Map;")
            accessFlags(AccessFlags.PUBLIC)
            returnType = "V"
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
            modifiers = Modifier.PUBLIC
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