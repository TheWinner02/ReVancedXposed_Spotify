package io.github.chsbuffer.revancedxposed.spotify.misc.login

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

object Fingerprints {

    fun findIntegrityCheck(bridge: DexKitBridge) = bridge.findMethod {
        // searchPackages va fuori da matcher { }
        searchPackages("com.spotify")

        matcher {
            returnType = "boolean"
            modifiers = Modifier.PUBLIC or Modifier.FINAL
            invokeMethods {
                add {
                    declaredClass("java.util.Calendar")
                    name("get")
                }
            }
        }
    }

    fun findClientDataMethods(bridge: DexKitBridge, methodName: String): List<MethodData> {
        return bridge.findMethod {
            // searchPackages va fuori da matcher { }
            searchPackages("com.spotify")

            matcher {
                returnType = "java.lang.String"
                modifiers = Modifier.PUBLIC

                // Per indicare "zero parametri" in DexKit si usa:
                params { }

                when (methodName) {
                    "getClientVersion" -> {
                        usingStrings("iphone-", "9.")
                    }
                    "getSystemVersion" -> {
                        // Cerchiamo i metodi che usano le costanti di versione
                        usingStrings("15", "16", "17")
                    }
                    "getHardwareMachine" -> {
                        // Cerchiamo i metodi che contengono i nomi dei modelli Apple
                        usingStrings("iPhone", "iPad")
                    }
                }
            }
        }.filter {
            // Filtro per nomi offuscati (solitamente 1 o 2 lettere)
            it.methodName.length <= 3
        }
    }
}