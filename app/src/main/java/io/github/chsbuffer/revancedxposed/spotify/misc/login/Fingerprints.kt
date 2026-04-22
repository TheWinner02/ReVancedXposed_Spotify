package io.github.chsbuffer.revancedxposed.spotify.misc.login

import io.github.chsbuffer.revancedxposed.AccessFlags
import io.github.chsbuffer.revancedxposed.FindMethodFunc
import io.github.chsbuffer.revancedxposed.fingerprint
import io.github.chsbuffer.revancedxposed.parameters

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

    // 3. MAPPA DI LOGIN
    // Usiamo una stringa specifica per aiutarlo a essere più unico,
    // ma manteniamo la struttura che piace al tuo compilatore.
    val loginMapFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            parameters("Ljava/util/Map;")
            returnType = "V"
            // Aggiungiamo i modificatori per restringere il campo dai 12 risultati
            accessFlags(AccessFlags.PUBLIC)
            usingStrings("App-Platform")
        }
    }
}
