package io.github.chsbuffer.revancedxposed.spotify.misc.login

import org.luckypray.dexkit.DexKitBridge

object Fingerprints {
    // Ora puntiamo alla classe p.llf0 e p.jlf0 usando le stringhe reali dell'APK Stock
    fun findIntegrityCheck(bridge: DexKitBridge) = bridge.findMethod {
        searchPackages("p")
        matcher {
            // Questa stringa appare nei tuoi log di grep_jlf0_strings.txt
            usingStrings("standard_pi_request", "outcome", "success")
        }
    }

    // Questo ne trovava già 4, lo rendiamo solo più solido
    fun findIntegrityProto(bridge: DexKitBridge) = bridge.findMethod {
        searchPackages("p", "com.google.android.play.core.integrity")
        matcher {
            name = "token"
            returnType = "java.lang.String"
            paramCount = 0
        }
    }
}