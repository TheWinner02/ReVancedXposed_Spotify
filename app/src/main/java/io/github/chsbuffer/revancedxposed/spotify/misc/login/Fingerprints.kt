package io.github.chsbuffer.revancedxposed.spotify.misc.login

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

object Fingerprints {

    fun findIntegrityCheck(bridge: DexKitBridge) = bridge.findMethod {
        matcher {
            returnType = "boolean"
            // Usiamo i bitmask Int per i modificatori
            modifiers = Modifier.PUBLIC or Modifier.FINAL
            invokeMethods {
                add {
                    declaredClass("java.util.Calendar")
                    name("get")
                }
            }
        }
    }

    fun findClientDataMethods(bridge: DexKitBridge, type: String): List<MethodData> {
        return when(type) {
            "getClientVersion" -> bridge.findMethod {
                matcher {
                    modifiers = Modifier.PUBLIC
                    returnType = "java.lang.String"
                    usingStrings("android/")
                }
            }

            "getSystemVersion" -> bridge.findMethod {
                matcher {
                    modifiers = Modifier.PUBLIC
                    returnType = "java.lang.String"
                    usingStrings("REL")
                }
            }

            "getHardwareMachine" -> bridge.findMethod {
                searchPackages("com.spotify.connectivity", "p")
                matcher {
                    modifiers = Modifier.PUBLIC
                    returnType = "java.lang.String"
                    usingStrings("unknown")
                }
            }
            else -> emptyList()
        }
    }

    fun findPlatformMethod(bridge: DexKitBridge): List<MethodData> {
        return bridge.findMethod {
            searchPackages("com.spotify.connectivity", "p")
            matcher {
                modifiers = Modifier.PUBLIC
                returnType = "java.lang.String"
                usingStrings("android")
                // Abbiamo rimosso instructionSize che causava l'errore.
                // Il filtro sul pacchetto e sulla stringa "android" è sufficiente.
            }
        }
    }

    fun findUserAgentSetter(bridge: DexKitBridge) = bridge.findMethod {
        searchPackages("com.spotify")
        matcher {
            returnType = "java.lang.String"
            usingStrings("Spotify/")
        }
    }
}