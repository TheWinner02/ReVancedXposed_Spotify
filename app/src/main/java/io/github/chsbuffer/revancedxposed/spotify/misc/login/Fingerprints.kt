package io.github.chsbuffer.revancedxposed.spotify.misc.login

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.matchers.MethodMatcher

object Fingerprints {

    /**
     * CERCA IL BYPASS INTEGRITÀ
     * Usa la sintassi esplicita per evitare errori di riferimento
     */
    fun findIntegrityCheck(bridge: DexKitBridge) = bridge.findMethod {
        val matcher = MethodMatcher.create()
            .returnType("boolean")
            .invokeMethods(
                MethodMatcher.create()
                    .className("java.util.Calendar")
                    .name("get")
            )

        matcher(matcher)
    }

    /**
     * CERCA I METODI PER LO SPOOF
     */
    fun findClientDataMethods(bridge: DexKitBridge, methodName: String) = bridge.findMethod {
        val matcher = MethodMatcher.create()
            .name(methodName)
            .returnType("java.lang.String")

        matcher(matcher)
    }
}