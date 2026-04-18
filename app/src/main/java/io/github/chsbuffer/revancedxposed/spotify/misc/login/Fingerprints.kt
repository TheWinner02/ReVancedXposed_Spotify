package io.github.chsbuffer.revancedxposed.spotify.misc.login

import io.github.chsbuffer.revancedxposed.strings
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

object Fingerprints {

    /**
     * CERCA IL BYPASS INTEGRITÀ
     */
    fun findIntegrityCheck(bridge: DexKitBridge) = bridge.findMethod {
        val matcher = MethodMatcher.create()
            .returnType("boolean")
            .invokeMethods {
                add {
                    declaredClass("java.util.Calendar")
                    name("get")
                }
            }

        matcher(matcher)
    }

    /**
     * CERCA I METODI PER LO SPOOF
     */
    fun findClientDataMethods(bridge: DexKitBridge, methodName: String): List<MethodData> {
        return bridge.findMethod {
            matcher {
                returnType = "java.lang.String"
                // Cerchiamo i metodi basandoci sulle stringhe che contengono
                when (methodName) {
                    "getClientVersion" -> strings("9.")
                    "getSystemVersion" -> strings("17.")
                    "getHardwareMachine" -> strings("iPhone")
                }
            }
        }
    }
}