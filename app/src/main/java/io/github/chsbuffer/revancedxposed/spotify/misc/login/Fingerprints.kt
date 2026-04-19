package io.github.chsbuffer.revancedxposed.spotify.misc.login

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

object Fingerprints {

    // IMPLEMENTAZIONE REALE REVANCED
    fun findIntegrityCheck(bridge: DexKitBridge) = bridge.findMethod {
        searchPackages("com.spotify") // Fuori dal matcher
        matcher {
            returnType = "void"
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
    fun findClientDataMethods(bridge: DexKitBridge, type: String): List<MethodData> {
        return when(type) {
            "getClientVersion" -> bridge.findMethod {
                matcher {
                    returnType = "java.lang.String"
                    usingStrings("android/")
                }
            }

            "getSystemVersion" -> bridge.findMethod {
                matcher {
                    returnType = "java.lang.String"
                    // Usiamo "REL" per centrare Build.VERSION.RELEASE in modo più mirato
                    usingStrings("REL")
                }
            }

            "getHardwareMachine" -> bridge.findMethod {
                // Spostato fuori dal matcher per risolvere l'errore del receiver
                searchPackages("com.spotify.connectivity", "p")
                matcher {
                    returnType = "java.lang.String"
                    usingStrings("unknown")
                }
            }

            else -> emptyList()
        }
    }

    // CERCA LA CLASSE DELLO USER AGENT
    fun findUserAgentSetter(bridge: DexKitBridge) = bridge.findMethod {
        searchPackages("com.spotify") // Fuori dal matcher
        matcher {
            name = "setDefaultHTTPUserAgent"
            paramTypes("java.lang.String")
        }
    }

    // CERCA IL METODO DELLA PIATTAFORMA
    fun findPlatformMethod(bridge: DexKitBridge): List<MethodData> {
        return bridge.findMethod {
            // Spostato fuori dal matcher per risolvere l'errore del receiver
            searchPackages("com.spotify.connectivity", "p")
            matcher {
                returnType = "java.lang.String"
                usingStrings("android")
            }
        }
    }
}