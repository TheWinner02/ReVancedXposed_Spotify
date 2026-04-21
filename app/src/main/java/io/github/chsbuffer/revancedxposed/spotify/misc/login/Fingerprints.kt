package io.github.chsbuffer.revancedxposed.spotify.misc.login

import io.github.chsbuffer.revancedxposed.AccessFlags
import io.github.chsbuffer.revancedxposed.FindMethodFunc
import io.github.chsbuffer.revancedxposed.Opcode
import io.github.chsbuffer.revancedxposed.fingerprint
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

object Fingerprints {

    // 1. CLIENT ID (Nome classe fisso, molto veloce)
    val setClientIdFingerprint: FindMethodFunc = fingerprint {
        classMatcher { descriptor = "Lcom/spotify/connectivity/ApplicationScopeConfiguration;" }
        methodMatcher {
            name = "setClientId"
            parameters("Ljava/lang/String;")
        }
    }

    // 2. USER AGENT (Nome classe fisso)
    val setUserAgentFingerprint: FindMethodFunc = fingerprint {
        classMatcher { descriptor = "Lcom/spotify/connectivity/ApplicationScopeConfiguration;" }
        methodMatcher {
            name = "setDefaultHTTPUserAgent"
            parameters("Ljava/lang/String;")
        }
    }

    // 3. ACCESS POINT (Fondamentale per far passare il traffico dal proxy locale)
    val setAccessPointFingerprint: FindMethodFunc = fingerprint {
        classMatcher { descriptor = "Lcom/spotify/connectivity/ApplicationScopeConfiguration;" }
        methodMatcher {
            name = "setAccessPoint"
            parameters("Ljava/lang/String;")
        }
    }

    // 4. PLATFORM SPOOF (Cerca chi restituisce "android" per forzare "ios")
    val loginClientPlatformFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            usingStrings("android", "client_id", "client_version")
        }
    }

    // 5. INTEGRITY BYPASS (Opcodes precisi per la versione 9.0.58)
    val runIntegrityVerificationFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
            returns("V")
            opcodes(
                Opcode.CHECK_CAST,
                Opcode.INVOKE_VIRTUAL,
                Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT_OBJECT,
                Opcode.INVOKE_VIRTUAL,
                Opcode.MOVE_RESULT,
                Opcode.IF_EQ
            )
        }
    }

    // 6. METODI GENERICI PER VERSIONI (Fallback se gli altri falliscono)
    // Questa funzione serve per lo spoofing "diffuso" di versioni e hardware
    fun findClientDataMethods(bridge: DexKitBridge, type: String): List<MethodData> {
        val searchString = when(type) {
            "getClientVersion" -> "android/"
            "getSystemVersion" -> "Android OS"
            "getHardwareMachine" -> "unknown"
            else -> return emptyList()
        }

        return bridge.findMethod {
            matcher {
                returnType = "java.lang.String"
                usingStrings(searchString)
            }
        }
    }
}