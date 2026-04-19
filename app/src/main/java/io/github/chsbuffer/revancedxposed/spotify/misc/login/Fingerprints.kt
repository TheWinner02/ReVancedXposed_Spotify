package io.github.chsbuffer.revancedxposed.spotify.misc.login

import org.luckypray.dexkit.DexKitBridge

object Fingerprints {
    // Cerchiamo il punto in cui Spotify elabora il token di integrità
    // Dai tuoi log: p.jlf0 e p.glf0 sono i candidati ideali
    fun findIntegrityCheck(bridge: DexKitBridge) = bridge.findMethod {
        searchPackages("p")
        matcher {
            // Cerchiamo chiunque interagisca con la libreria StandardIntegrity di Google
            usingStrings("com.google.android.play.core.integrity.StandardIntegrityManager")
        }
    }

    // Cerchiamo il metodo specifico che restituisce la stringa del Token
    // In result1.txt abbiamo visto: StandardIntegrityToken;->token()Ljava/lang/String;
    fun findIntegrityProto(bridge: DexKitBridge) = bridge.findMethod {
        searchPackages("p", "com.google.android.play.core.integrity")
        matcher {
            name = "token"
            returnType = "java.lang.String"
            // Spesso non ha parametri perché è un getter
            paramCount = 0
        }
    }
}