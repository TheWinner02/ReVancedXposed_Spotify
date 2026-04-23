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
    private const val CLIENT_VERSION = "iphone-9.0.58"
    private const val SYSTEM_VERSION = "17.7.2"
    private const val HARDWARE_MACHINE = "iPhone16,1"

    private const val IOS_USER_AGENT = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"

    // ORA RESTITUISCE ByteArray? INVECE DI ClientTokenResponse?
    fun serveClientTokenRequest(inputStream: InputStream): ByteArray? {
        return try {
            val bytes = inputStream.readBytes()
            XposedBridge.log("SPOOF-PROXY: Ricevuta richiesta Protobuf (${bytes.size} bytes)")
            val request = ProtoBuf.decodeFromByteArray<ClientTokenRequest>(bytes)
            XposedBridge.log("SPOOF-PROXY: Tipo richiesta originale: ${request.requestType}")

            val responseBytes = getClientTokenResponse(request)
            if (responseBytes != null) {
                XposedBridge.log("SPOOF-PROXY: Risposta raw da Spotify ricevuta con successo (${responseBytes.size} bytes)")
            }
            responseBytes
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-PROXY: Errore nel servire la richiesta clienttoken: ${e.message}")
            null
        }
    }

    private fun getClientTokenResponse(originalRequest: ClientTokenRequest): ByteArray? {
        var request = originalRequest
        if (request.requestType == ClientTokenRequestType.REQUEST_CLIENT_DATA_REQUEST) {
            XposedBridge.log("SPOOF-PROXY: Trasformazione richiesta Android -> iOS")
            val deviceId = request.clientData?.connectivitySdkData?.deviceId ?: ""
            request = newIOSClientTokenRequest(deviceId)
        }

        return try {
            requestClientToken(request)
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-PROXY: Errore durante l'inoltro a Spotify: ${e.message}")
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

    private fun requestClientToken(clientTokenRequest: ClientTokenRequest): ByteArray? {
        val bodyBytes = ProtoBuf.encodeToByteArray(clientTokenRequest)

        return try {
            val url = URL("https://clienttoken.spotify.com/v1/clienttoken")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            connection.setRequestProperty("Content-Type", "application/x-protobuf")
            connection.setRequestProperty("Accept", "application/x-protobuf")
            connection.setRequestProperty("User-Agent", IOS_USER_AGENT)
            connection.setRequestProperty("X-Client-Id", IOS_CLIENT_ID)
            connection.setRequestProperty("App-Platform", "ios")

            connection.outputStream.use { it.write(bodyBytes) }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val responseBytes = connection.inputStream.readBytes()
                XposedBridge.log("SPOOF-PROXY: Risposta da Spotify ricevuta (${responseBytes.size} bytes)")
                responseBytes
            } else {
                val errorMsg = connection.errorStream?.bufferedReader()?.readText() ?: connection.responseMessage
                XposedBridge.log("SPOOF-PROXY: Errore risposta Spotify ($responseCode): $errorMsg")
                null
            }
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-PROXY: Errore durante richiesta clienttoken (HttpURLConnection): ${e.message}")
            e.printStackTrace()
            null
        }
    }
}