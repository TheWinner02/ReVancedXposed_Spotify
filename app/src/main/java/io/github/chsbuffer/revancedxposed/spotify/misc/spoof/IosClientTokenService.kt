package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import de.robv.android.xposed.XposedBridge
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalSerializationApi::class)
object IosClientTokenService {
    private const val IOS_CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3"
    private const val CLIENT_VERSION = "iphone-9.0.58.558.g200011c"
    private const val SYSTEM_VERSION = "17.7.2"
    private const val HARDWARE_MACHINE = "iPhone16,1"

    private const val IOS_USER_AGENT = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"

    fun serveClientTokenRequest(inputStream: InputStream, originalHeaders: Map<String, String>): ByteArray? {
        return try {
            val bytes = inputStream.readBytes()
            XposedBridge.log("SPOOF-PROXY: Ricevuta richiesta Protobuf (${bytes.size} bytes)")
            
            val request = try {
                ProtoBuf.decodeFromByteArray<ClientTokenRequest>(bytes)
            } catch (_: Exception) {
                null
            }

            if (request != null) {
                XposedBridge.log("SPOOF-PROXY: Tipo richiesta originale: ${request.requestType}")
                
                // Trasformiamo SEMPRE CLIENT_DATA_REQUEST per garantire che il corpo sia 100% iOS
                if (request.requestType == ClientTokenRequestType.REQUEST_CLIENT_DATA_REQUEST) {
                    XposedBridge.log("SPOOF-PROXY: Trasformazione CLIENT_DATA -> Full iOS")
                    val deviceId = request.clientData?.connectivitySdkData?.deviceId ?: ""
                    val transformedRequest = newIOSClientTokenRequest(deviceId)
                    val bodyBytes = ProtoBuf.encodeToByteArray(transformedRequest)
                    
                    // Usiamo header iOS perché il corpo è iOS
                    return requestClientTokenRaw(bodyBytes, useIosHeaders = true, originalHeaders)
                }
            }
            
            // Per Challenge o altro, inoltriamo byte originali con header originali (IMPORTANTE per evitare 400)
            XposedBridge.log("SPOOF-PROXY: Inoltro byte originali (Challenge o altro). Preservo coerenza header/body.")
            requestClientTokenRaw(bytes, useIosHeaders = false, originalHeaders)
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-PROXY: Errore nel servire la richiesta: ${e.message}")
            null
        }
    }

    private fun newIOSClientTokenRequest(deviceId: String): ClientTokenRequest {
        XposedBridge.log("SPOOF-PROXY: Creazione nuovo token request iOS per device: $deviceId")
        val iosData = NativeIOSData(hwMachine = HARDWARE_MACHINE, systemVersion = SYSTEM_VERSION)
        val platformData = PlatformSpecificData(ios = iosData)
        val sdkData = ConnectivitySdkData(deviceId = deviceId, platformSpecificData = platformData)
        val clientData = ClientDataRequest(clientId = IOS_CLIENT_ID, clientVersion = CLIENT_VERSION, connectivitySdkData = sdkData)

        return ClientTokenRequest(
            requestType = ClientTokenRequestType.REQUEST_CLIENT_DATA_REQUEST,
            clientData = clientData
        )
    }

    private fun requestClientTokenRaw(bodyBytes: ByteArray, useIosHeaders: Boolean, originalHeaders: Map<String, String>): ByteArray? {
        return try {
            val url = URL("https://clienttoken.spotify.com/v1/clienttoken")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // 1. Applichiamo header originali passati dall'app (importante per le Challenge)
            originalHeaders.forEach { (key, value) ->
                if (!key.equals("host", ignoreCase = true) && 
                    !key.equals("content-length", ignoreCase = true) &&
                    !key.equals("connection", ignoreCase = true)) {
                    connection.setRequestProperty(key, value)
                }
            }

            // 2. Se stiamo inviando un corpo trasformato, forziamo header iOS
            if (useIosHeaders) {
                connection.setRequestProperty("Content-Type", "application/x-protobuf")
                connection.setRequestProperty("Accept", "application/x-protobuf")
                connection.setRequestProperty("User-Agent", IOS_USER_AGENT)
                connection.setRequestProperty("X-Client-Id", IOS_CLIENT_ID)
                connection.setRequestProperty("App-Platform", "ios")
            }

            connection.outputStream.use { it.write(bodyBytes) }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val responseBytes = connection.inputStream.readBytes()
                XposedBridge.log("SPOOF-PROXY: Risposta ricevuta (${responseBytes.size} bytes)")
                responseBytes
            } else {
                val errorBytes = connection.errorStream?.readBytes()
                val errorMsg = errorBytes?.let { String(it) } ?: connection.responseMessage
                XposedBridge.log("SPOOF-PROXY: Errore risposta Spotify ($responseCode): $errorMsg")
                null
            }
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-PROXY: Errore durante richiesta clienttoken: ${e.message}")
            null
        }
    }
}