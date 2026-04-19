package io.github.chsbuffer.revancedxposed.spotify.misc.login

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

object Fingerprints {

    // IMPLEMENTAZIONE REALE REVANCED: Cerca il metodo void che usa Calendar.get
    fun findIntegrityCheck(bridge: DexKitBridge) = bridge.findMethod {
        searchPackages("com.spotify")
        matcher {
            returnType = "void" // <-- ReVanced usa V (void), non boolean!
            modifiers = Modifier.PUBLIC or Modifier.FINAL
            invokeMethods {
                add {
                    declaredClass("java.util.Calendar")
                    name("get")
                }
            }
        }
    }

    // CERCA I METODI DI SPOOF
    fun findClientDataMethods(bridge: DexKitBridge, methodName: String): List<MethodData> {
        return bridge.findMethod {
            searchPackages("com.spotify")
            matcher {
                returnType = "java.lang.String"
                modifiers = Modifier.PUBLIC
                params { } // Getter senza argomenti

                when (methodName) {
                    "getClientVersion" -> usingStrings("iphone-", "9.")
                    "getSystemVersion" -> usingStrings("15", "16", "17")
                    "getHardwareMachine" -> usingStrings("iPhone", "iPad")
                }
            }
        }.filter { it.methodName.length <= 3 }
    }

    // CERCA LA CLASSE DELLO USER AGENT (Per evitare l'errore "non trovata")
    fun findUserAgentSetter(bridge: DexKitBridge) = bridge.findMethod {
        searchPackages("com.spotify")
        matcher {
            name = "setDefaultHTTPUserAgent"
            paramTypes("java.lang.String")
        }
    }

    // In Fingerprints.kt
    fun findPlatformMethod(bridge: DexKitBridge): List<MethodData> {
        return bridge.findMethod {
            // Invece di tutto com.spotify, cerchiamo solo nel pacchetto connectivity
            // dove abbiamo visto trovarsi le classi interessanti nei tuoi log
            searchPackages("com.spotify.connectivity", "p")
            matcher {
                returnType = "java.lang.String"
                usingStrings("android")
            }
        }
    }
}