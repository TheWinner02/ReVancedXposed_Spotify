package io.github.chsbuffer.revancedxposed.spotify.misc.login

import io.github.chsbuffer.revancedxposed.AccessFlags
import io.github.chsbuffer.revancedxposed.FindMethodFunc
import io.github.chsbuffer.revancedxposed.Opcode
import io.github.chsbuffer.revancedxposed.fingerprint

object Fingerprints {
    val setClientIdFingerprint: FindMethodFunc = fingerprint {
        classMatcher { descriptor = "Lcom/spotify/connectivity/ApplicationScopeConfiguration;" }
        methodMatcher {
            name = "setClientId"
            parameters("Ljava/lang/String;")
        }
    }

    val setUserAgentFingerprint: FindMethodFunc = fingerprint {
        classMatcher { descriptor = "Lcom/spotify/connectivity/ApplicationScopeConfiguration;" }
        methodMatcher {
            name = "setDefaultHTTPUserAgent"
            parameters("Ljava/lang/String;")
        }
    }

    val setAccessPointFingerprint: FindMethodFunc = fingerprint {
        classMatcher { descriptor = "Lcom/spotify/connectivity/ApplicationScopeConfiguration;" }
        methodMatcher {
            name = "setAccessPoint"
            parameters("Ljava/lang/String;")
        }
    }

    val loginClientPlatformFingerprint: FindMethodFunc = fingerprint {
        methodMatcher {
            usingStrings("android", "client_id", "client_version")
        }
    }

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
}