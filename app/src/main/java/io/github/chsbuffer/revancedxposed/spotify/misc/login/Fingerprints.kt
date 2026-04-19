package io.github.chsbuffer.revancedxposed.spotify.misc.login

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

object Fingerprints {

    // IMPLEMENTAZIONE REALE REVANCED: Cerca il metodo void che usa Calendar.get

    fun findIntegrityCheck(bridge: DexKitBridge) = bridge.findMethod {
        searchPackages("com.spotify")
        matcher {
            invokeMethods {
                add {
                    declaredClass("java.util.Calendar")
                    name("get")
                }
            }
        }
    }

    // CERCA I METODI DI SPOOF
    fun findClientDataMethods(bridge: DexKitBridge, type: String): List<MethodData> {
        val searchString = when(type) {
            // "android/" lo abbiamo trovato nel tuo grep sullo stock
            "getClientVersion" -> "android/"
            // Cerchiamo le stringhe che Spotify Android usa per identificare il sistema
            // Invece di usare i campi Build, cerchiamo chi maneggia queste versioni comuni
            "getSystemVersion" -> "REL" // Prova con "12", "13" o "14" (le versioni Android stock)

            // Spotify restituisce quasi sempre "unknown" o "google" se non riconosce l'hardware
            "getHardwareMachine" -> "unknown"
            else -> return emptyList()
        }

        return bridge.findMethod {
            matcher {
                returnType = "java.lang.String"
                usingStrings(searchString)
            }
        }
    }

    // CERCA LA CLASSE DELLO USER AGENT (Per evitare l'errore "non trovata")
    fun findUserAgentSetter(bridge: DexKitBridge) = bridge.findMethod {
        searchPackages("com.spotify")
        matcher {
            name = "setDefaultHTTPUserAgent"
            paramTypes("java.lang.String")
        }
    }

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