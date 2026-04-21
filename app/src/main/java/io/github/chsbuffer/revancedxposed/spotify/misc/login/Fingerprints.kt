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
            usingStrings("accesspoint", "http://")
            parameters("Ljava/lang/String;")
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

    // 6. ORBIT LIBRARY LOAD
    val orbitLibraryFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            usingStrings("orbit-jni-spotify")
        }
    }

    // 7. CONFIG MAPS (Deep Spoof)
    val loginMapFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            parameters("Ljava/util/Map;")
            accessFlags(AccessFlags.PUBLIC)
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
}