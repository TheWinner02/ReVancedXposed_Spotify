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
    private const val CLIENT_VERSION = "iphone-9.0.58.558.g200011c"
    private const val SYSTEM_VERSION = "17.7.2"
    private const val HARDWARE_MACHINE = "iPhone16,1"
    private const val IOS_USER_AGENT = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"
    
    // IP diretto di Spotify ClientToken per bypassare blocchi DNS
    private const val FALLBACK_IP = "35.186.224.24"

    fun serveClientTokenRequest(inputStream: InputStream, originalHeaders: Map<String, String>): ByteArray? {
        return try {
            val bytes = inputStream.readBytes()
            XposedBridge.log("SPOOF-PROXY: Ricevuta richiesta Protobuf (${bytes.size} bytes)")
            
            val request = try {
                ProtoBuf.decodeFromByteArray<ClientTokenRequest>(bytes)
            } catch (_: Exception) {
                null
            }

                if (request != null && (request.requestType == ClientTokenRequestType.REQUEST_CLIENT_DATA_REQUEST)) {
                    XposedBridge.log("SPOOF-PROXY: Trasformazione CLIENT_DATA -> Full iOS")
                    val originalDeviceId = request.clientData?.connectivitySdkData?.deviceId ?: ""
                    
                    // Generiamo un vero UUID in formato iOS (costante per lo stesso deviceId Android)
                    val iosDeviceId = UUID.nameUUIDFromBytes(originalDeviceId.toByteArray())
                        .toString().uppercase()
                    
                    val transformedRequest = newIOSClientTokenRequest(iosDeviceId)
                    val bodyBytes = ProtoBuf.encodeToByteArray(transformedRequest)
                    
                    XposedBridge.log("SPOOF-PROXY: Inviando richiesta iOS (DeviceID: $iosDeviceId)")
                    return requestClientTokenRaw(bodyBytes, useIosHeaders = true, originalHeaders)
                }
            
            XposedBridge.log("SPOOF-PROXY: Inoltro byte originali (Challenge o altro).")
            requestClientTokenRaw(bytes, useIosHeaders = false, originalHeaders)
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-PROXY: Errore critico nel servire la richiesta: ${e.message}")
            null
        }
    }

    private fun newIOSClientTokenRequest(deviceId: String): ClientTokenRequest {
        val iosData = NativeIOSData(hwMachine = HARDWARE_MACHINE, systemVersion = SYSTEM_VERSION)
        val platformData = PlatformSpecificData(ios = iosData)
        val sdkData = ConnectivitySdkData(deviceId = deviceId, platformSpecificData = platformData)
        val clientData = ClientDataRequest(clientId = IOS_CLIENT_ID, clientVersion = CLIENT_VERSION, connectivitySdkData = sdkData)

        return ClientTokenRequest(
            clientData = clientData,
            requestType = ClientTokenRequestType.REQUEST_CLIENT_DATA_REQUEST
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

                originalHeaders.forEach { (key, value) ->
                    if (!key.equals("host", ignoreCase = true) && 
                        !key.equals("content-length", ignoreCase = true) &&
                        !key.equals("connection", ignoreCase = true)) {
                        connection.setRequestProperty(key, value)
                    }
                }

                if (useIosHeaders) {
                    connection.setRequestProperty("Content-Type", "application/x-protobuf")
                    connection.setRequestProperty("Accept", "application/x-protobuf")
                    connection.setRequestProperty("User-Agent", IOS_USER_AGENT)
                    connection.setRequestProperty("X-Client-Id", IOS_CLIENT_ID)
                    connection.setRequestProperty("App-Platform", "ios")
                }

                connection.outputStream.use { it.write(bodyBytes) }

                if (connection.responseCode == 200) {
                    val responseBytes = connection.inputStream.readBytes()
                    
                    // VALIDAZIONE E LOG DETTAGLIATO
                    runCatching {
                        val resp = ProtoBuf.decodeFromByteArray<ClientTokenResponse>(responseBytes)
                        val expires = resp.grantedToken?.expiresAfterSeconds ?: 0
                        val tokenPreview = resp.grantedToken?.token?.take(10) ?: "null"
                        XposedBridge.log("SPOOF-PROXY: Token iOS ottenuto! (Exp: ${expires}s, Preview: $tokenPreview...)")
                    }.onFailure {
                        XposedBridge.log("SPOOF-PROXY: Errore decodifica risposta: ${it.message}")
                    }

                    XposedBridge.log("SPOOF-PROXY: Successo! Ricevuti ${responseBytes.size} byte.")
                    return responseBytes
                } else {
                    XposedBridge.log("SPOOF-PROXY: Server ha risposto con ${connection.responseCode}")
                }
            } catch (e: Exception) {
                XposedBridge.log("SPOOF-PROXY: Fallimento su $address: ${e.message}")
            }
        }
        return null
    }
}