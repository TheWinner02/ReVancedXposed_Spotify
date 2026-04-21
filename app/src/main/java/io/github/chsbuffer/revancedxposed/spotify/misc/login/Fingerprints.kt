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

    // 1. CLIENT ID (Metodo diretto)
    val setClientIdFingerprint: FindMethodFunc = fingerprint {
        classMatcher { descriptor = "Lcom/spotify/connectivity/ApplicationScopeConfiguration;" }
        methodMatcher {
            name = "setClientId"
            parameters("Ljava/lang/String;")
        }
    }

    // 2. USER AGENT (Metodo diretto)
    val setUserAgentFingerprint: FindMethodFunc = fingerprint {
        classMatcher { descriptor = "Lcom/spotify/connectivity/ApplicationScopeConfiguration;" }
        methodMatcher {
            name = "setDefaultHTTPUserAgent"
            parameters("Ljava/lang/String;")
        }
    }

    // 3. ACCESS POINT (Risolve SPOOF ERROR: Fingerprint AccessPoint non trovato)
    val setAccessPointFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            // Cerchiamo un metodo che accetta una String e ha a che fare con la rete
            // Spesso il nome è 'setAccessPoint' ma potrebbe essere offuscato
            usingStrings("accesspoint", "http://")
            parameters("Ljava/lang/String;")
        }
    }

    // 4. PLATFORM SPOOF (Risolve SPOOF ERROR: Fingerprint Platform non trovato)
    val loginClientPlatformFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            returnType = "Ljava/lang/String;"
            usingStrings("android")
            parameters()
            accessFlags(AccessFlags.PUBLIC)
        }
    }

    // 5. INTEGRITY BYPASS (Opcodes originali)
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

    // 6. METODI GENERICI (Ottimizzati per evitare i "39 metodi" del log)
    fun findClientDataMethods(bridge: DexKitBridge, type: String): List<MethodData> {
        return bridge.findMethod {
            matcher {
                returnType = "java.lang.String"
                parameters()
                modifiers = Modifier.PUBLIC

                when(type) {
                    // Da client.txt
                    "getClientVersion" -> usingStrings("android/")
                    // Da hardware.txt
                    "getSystemVersion" -> usingStrings("release")
                    "getHardwareMachine" -> usingStrings("model")
                }
            }
        }
    }
}