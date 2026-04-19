package io.github.chsbuffer.revancedxposed.spotify.misc.login

import org.luckypray.dexkit.DexKitBridge

object Fingerprints {
    // Cerchiamo la classe che istanzia il manager di Google
    fun findIntegrityCheck(bridge: DexKitBridge) = bridge.findMethod {
        searchPackages("p")
        matcher {
            // Cerchiamo chiunque usi la stringa "cloud_project_number" o riferimenti a Integrity
            // Proviamo a cercare la stringa più generica che abbiamo visto nei grep
            usingStrings("com.google.android.play.core.integrity")
        }
    }

    fun findIntegrityProto(bridge: DexKitBridge) = bridge.findMethod {
        searchPackages("p", "com.google.android.play.core.integrity")
        matcher {
            name = "token"
            returnType = "java.lang.String"
            paramCount = 0
        }
    }
}