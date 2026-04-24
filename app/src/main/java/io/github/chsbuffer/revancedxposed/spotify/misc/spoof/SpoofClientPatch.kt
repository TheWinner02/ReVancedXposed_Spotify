package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

fun SpoofClient(lpparam: XC_LoadPackage.LoadPackageParam) {
    val classLoader = lpparam.classLoader
    
    // Identità iOS Master (Allineata con sorgenti Pro)
    val iosClientId = "58bd3c95768941ea9eb4350aaa033eb3"
    val iosUserAgent = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"

    XposedBridge.log("SPOOF-CLIENT: Modalità Stealth Trasparente (Solo Header)")

    // Rimosso Signature Spoof e Proxy per evitare l'errore "Si è verificato un problema"
    // e garantire la stabilità totale della connessione.

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

                    // Colpiamo i due punti critici per l'identità
                    if (url.contains("login5.spotify.com/v4/login") || 
                        url.contains("clienttoken.spotify.com/v1/clienttoken") ||
                        url.contains("spclient.wg.spotify.com")) {
                        
                        runCatching {
                            val headersField = req.javaClass.declaredFields.find { 
                                it.type == Map::class.java || it.type.name.contains("headers", ignoreCase = true) 
                            }
                            headersField?.let {
                                it.isAccessible = true
                                @Suppress("UNCHECKED_CAST")
                                val map = it.get(req) as? MutableMap<String, String>
                                map?.let { m ->
                                    m["User-Agent"] = iosUserAgent
                                    m["App-Platform"] = "ios"
                                    m["X-Client-Id"] = iosClientId
                                    XposedBridge.log("SPOOF-CLIENT: Header Master iOS iniettati -> $url")
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}
