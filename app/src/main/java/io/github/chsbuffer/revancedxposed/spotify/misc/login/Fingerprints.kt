package io.github.chsbuffer.revancedxposed.spotify.misc.login

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData

object Fingerprints {
    // Cerchiamo solo le chiamate all'integrità Google
    fun findIntegrityCheck(bridge: DexKitBridge): List<MethodData> = bridge.findMethod {
        searchPackages("p", "com.google.android.play.core.integrity")
        matcher { usingStrings("cloud_project_number") }
    }

    // Cerchiamo il pacchetto che invia il token alla liborbit
    fun findIntegrityProto(bridge: DexKitBridge): List<MethodData> = bridge.findMethod {
        searchPackages("com.spotify.integrity")
        matcher { name = "setToken" }
    }
}