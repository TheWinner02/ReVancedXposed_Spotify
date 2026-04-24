package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

fun SpoofClient(lpparam: XC_LoadPackage.LoadPackageParam) {
    val classLoader = lpparam.classLoader
    
    XposedBridge.log("SPOOF-DEBUG: Modalità Analisi Traffico Attiva (Zero Spoofing)")

    // Hook NativeHttpConnection per vedere COSA fa l'app originale
    runCatching {
        val cl = classLoader
        val httpConnectionImpl = cl.loadClass("com.spotify.core.http.NativeHttpConnection")
        val httpRequest = cl.loadClass("com.spotify.core.http.HttpRequest")
        val urlField = httpRequest.getDeclaredField("url").apply { isAccessible = true }

        XposedBridge.hookAllMethods(
            httpConnectionImpl,
            "send",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args[0]
                    val url = (urlField.get(req) as? String) ?: return

                    // Logghiamo TUTTE le chiamate sospette per capire il flusso originale
                    if (url.contains("clienttoken") || url.contains("login") || url.contains("auth")) {
                        XposedBridge.log("SPOOF-DEBUG: Request -> $url")
                        
                        // Tentiamo di vedere la dimensione del corpo per identificare il Protobuf
                        runCatching {
                            val bodyField = req.javaClass.declaredFields.find { it.type == ByteArray::class.java }
                            bodyField?.let {
                                it.isAccessible = true
                                val body = it.get(req) as? ByteArray
                                XposedBridge.log("SPOOF-DEBUG: Body Size -> ${body?.size ?: 0} bytes")
                            }
                        }
                    }
                }
            }
        )
        XposedBridge.log("SPOOF-DEBUG: Monitor di rete installato con successo")
    }
}
