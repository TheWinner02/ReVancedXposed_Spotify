package io.github.chsbuffer.revancedxposed.spotify.misc.login

import org.luckypray.dexkit.DexKitBridge

object Fingerprints {

    /**
     * Equivalente di getRunIntegrityVerificationFingerprint() in ReVanced.
     * Cerca il metodo nativo di Spotify che usa Calendar per i check temporali di integrità.
     */
    fun findIntegrityCheck(bridge: DexKitBridge) = bridge.findMethod {
        matcher {
            usingStrings("Ljava/util/Calendar;", "get")
            returnType = "Z" // Cerca un metodo che restituisce un Booleano (True/False)
        }
    }

    /**
     * Equivalente della ricerca in SpoofClientPatchKt.
     * Cerca i metodi interni di Spotify che restituiscono le info del dispositivo.
     */
    fun findClientDataMethods(bridge: DexKitBridge, methodName: String) = bridge.findMethod {
        matcher {
            name = methodName
            returnType = "java.lang.String" // Tutti questi metodi restituiscono stringhe
        }
    }
}