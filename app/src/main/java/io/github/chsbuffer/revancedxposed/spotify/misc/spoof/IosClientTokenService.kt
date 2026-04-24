package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import de.robv.android.xposed.XposedBridge
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.InputStream
import java.net.InetAddress
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier

@OptIn(ExperimentalSerializationApi::class)
object IosClientTokenService {
    private const val IOS_CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3" 
    private const val CLIENT_VERSION = "iphone-8.8.84.502" // Versione più stabile e meno soggetta a Challenge forzati
    private const val SYSTEM_VERSION = "17.7.2"
    private const val HARDWARE_MACHINE = "iPhone16,1"
    private const val IOS_USER_AGENT = "Spotify/8.8.84 iOS/17.7.2 (iPhone16,1)"
    
    private const val STATIC_IOS_DEVICE_ID = "2A084F20-1307-3AE0-83C8-AE5CA4AB5CD0"
    
    // IP diretto di Spotify ClientToken per bypassare blocchi DNS
    private const val FALLBACK_IP = "35.186.224.24"

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    fun serveClientTokenRequest(inputStream: InputStream, originalHeaders: Map<String, String>): ByteArray? {
        return try {
            val bytes = inputStream.readBytes()
            XposedBridge.log("SPOOF-DEBUG: [1/4] Android Request (Hex): ${bytes.toHexString()}")
            
            // FORZA IDENTITÀ IOS STATICA (MASTER)
            val transformedRequest = newIOSClientTokenRequest(STATIC_IOS_DEVICE_ID)
            val bodyBytes = ProtoBuf.encodeToByteArray(transformedRequest)
            XposedBridge.log("SPOOF-DEBUG: [2/4] iOS Master Request (Hex): ${bodyBytes.toHexString()}")
            
            XposedBridge.log("SPOOF-DEBUG: [3/4] Sending iOS Request to Spotify...")
            val response = requestClientTokenRaw(bodyBytes, useIosHeaders = true, originalHeaders)
            
            if (response != null) {
                XposedBridge.log("SPOOF-DEBUG: [4/4] Response received (${response.size} bytes)")
            }
            return response
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-DEBUG: Proxy Exception: ${e.message}")
            null
        }
    }

    private fun newIOSClientTokenRequest(deviceId: String): ClientTokenRequest {
        val iosData = NativeIOSData(
            userInterfaceIdiom = 0, // 0 = Phone
            targetIphoneSimulator = false,
            hwMachine = HARDWARE_MACHINE, 
            systemVersion = SYSTEM_VERSION,
            simulatorModelIdentifier = ""
        )
        val platformData = PlatformSpecificData(ios = iosData)
        val sdkData = ConnectivitySdkData(deviceId = deviceId, platformSpecificData = platformData)
        val clientData = ClientDataRequest(clientId = IOS_CLIENT_ID, clientVersion = CLIENT_VERSION, connectivitySdkData = sdkData)

        return ClientTokenRequest(
            requestType = ClientTokenRequestType.REQUEST_CLIENT_DATA_REQUEST,
            clientData = clientData
        )
    }

    private fun requestClientTokenRaw(bodyBytes: ByteArray, useIosHeaders: Boolean, originalHeaders: Map<String, String>): ByteArray? {
        val host = "clienttoken.spotify.com"
        
        // Strategia: Prima proviamo DNS, poi IP diretto con Bypass SSL
        val addresses = mutableListOf<String>()
        val dnsAddress = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
        dnsAddress?.let { addresses.add(it) }
        addresses.add(FALLBACK_IP)

        for (address in addresses.distinct()) {
            try {
                XposedBridge.log("SPOOF-PROXY: Tentativo connessione a $address")
                
                val url = URL("https://$address/v1/clienttoken")
                val connection = url.openConnection() as HttpsURLConnection
                
                // Bypass validazione hostname per permettere l'uso dell'IP diretto con SSL Spotify
                connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
                
                connection.setRequestProperty("Host", host)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.useCaches = false

                // PULIZIA TOTALE HEADER ORIGINALI
                if (!useIosHeaders) {
                    originalHeaders.forEach { (key, value) ->
                        if (!key.equals("host", ignoreCase = true) && 
                            !key.equals("content-length", ignoreCase = true) &&
                            !key.equals("connection", ignoreCase = true)) {
                            connection.setRequestProperty(key, value)
                        }
                    }
                } else {
                    val headers = mapOf(
                        "Content-Type" to "application/x-protobuf",
                        "Accept" to "application/x-protobuf",
                        "User-Agent" to IOS_USER_AGENT,
                        "X-Client-Id" to IOS_CLIENT_ID,
                        "App-Platform" to "ios",
                        "Accept-Language" to "en-US,en;q=0.9"
                    )
                    headers.forEach { (k, v) -> 
                        connection.setRequestProperty(k, v)
                        XposedBridge.log("SPOOF-DEBUG: Header -> $k: $v")
                    }
                }

                connection.outputStream.use { it.write(bodyBytes) }

                if (connection.responseCode == 200) {
                    val responseBytes = connection.inputStream.readBytes()
                    
                    // DEBUG DETTAGLIATO RITORNO
                    runCatching {
                        val resp = ProtoBuf.decodeFromByteArray<ClientTokenResponse>(responseBytes)
                        if (resp.responseType == ClientTokenResponseType.RESPONSE_CHALLENGES_RESPONSE) {
                            XposedBridge.log("SPOOF-DEBUG: RITORNO -> CHALLENGE rilevato (Risoluzione Android necessaria)")
                        } else if (resp.responseType == ClientTokenResponseType.RESPONSE_GRANTED_TOKEN_RESPONSE) {
                            val expires = resp.grantedToken?.expiresAfterSeconds ?: 0
                            XposedBridge.log("SPOOF-DEBUG: RITORNO -> TOKEN iOS ottenuto con successo! Scadenza: ${expires}s")
                        }
                    }.onFailure {
                        XposedBridge.log("SPOOF-DEBUG: RITORNO -> Errore decodifica Protobuf, ma server ha risposto 200.")
                    }

                    return responseBytes
                } else {
                    val errorBody = connection.errorStream?.readBytes()?.decodeToString() ?: "Nessun corpo errore"
                    XposedBridge.log("SPOOF-DEBUG: RITORNO -> ERRORE SERVER ${connection.responseCode}: $errorBody")
                }
            } catch (e: Exception) {
                XposedBridge.log("SPOOF-PROXY: Fallimento su $address: ${e.message}")
            }
        }
        return null
    }
}