package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import de.robv.android.xposed.XposedBridge
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.InputStream
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier

@OptIn(ExperimentalSerializationApi::class)
object IosClientTokenService {
    private const val IOS_CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3"
    private const val CLIENT_VERSION = "iphone-8.8.84.502"
    private const val SYSTEM_VERSION = "17.7.2"
    private const val HARDWARE_MACHINE = "iPhone16,1"
    private const val IOS_USER_AGENT = "Spotify/8.8.84 iOS/17.7.2 (iPhone16,1)"
    private const val STATIC_IOS_DEVICE_ID = "2A084F20-1307-3AE0-83C8-AE5CA4AB5CD0"

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    fun serveClientTokenRequest(inputStream: InputStream, originalHeaders: Map<String, String>): ByteArray? {
        return try {
            val bytes = inputStream.readBytes()
            XposedBridge.log("SPOOF-EXPERIMENT: Android Request (Hex): ${bytes.toHexString()}")

            val iosData = NativeIOSData(userInterfaceIdiom = 0, targetIphoneSimulator = false, hwMachine = HARDWARE_MACHINE, systemVersion = SYSTEM_VERSION, simulatorModelIdentifier = "")
            val transformedRequest = ClientTokenRequest(
                requestType = ClientTokenRequestType.REQUEST_CLIENT_DATA_REQUEST,
                clientData = ClientDataRequest(
                    clientVersion = CLIENT_VERSION,
                    clientId = IOS_CLIENT_ID,
                    connectivitySdkData = ConnectivitySdkData(deviceId = STATIC_IOS_DEVICE_ID, platformSpecificData = PlatformSpecificData(ios = iosData))
                )
            )
            val iosBody = ProtoBuf.encodeToByteArray(transformedRequest)
            XposedBridge.log("SPOOF-EXPERIMENT: iOS Master Request (Hex): ${iosBody.toHexString()}")

            val response = requestClientTokenRaw(iosBody)
            if (response != null) {
                XposedBridge.log("SPOOF-EXPERIMENT: Token iOS ottenuto con successo (${response.size} bytes)")
                runCatching {
                    val resp = ProtoBuf.decodeFromByteArray<ClientTokenResponse>(response)
                    if (resp.responseType == ClientTokenResponseType.RESPONSE_CHALLENGES_RESPONSE) {
                        XposedBridge.log("SPOOF-EXPERIMENT: RITORNO -> CHALLENGE rilevato!")
                    }
                }
            }
            return response
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-EXPERIMENT: Errore nel servizio: ${e.message}")
            null
        }
    }

    private fun requestClientTokenRaw(bodyBytes: ByteArray): ByteArray? {
        val host = "clienttoken.spotify.com"
        return try {
            val url = URL("https://$host/v1/clienttoken")
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            
            // Header iOS puri per il proxy
            connection.setRequestProperty("Content-Type", "application/x-protobuf")
            connection.setRequestProperty("Accept", "application/x-protobuf")
            connection.setRequestProperty("User-Agent", IOS_USER_AGENT)
            connection.setRequestProperty("X-Client-Id", IOS_CLIENT_ID)
            connection.setRequestProperty("App-Platform", "ios")

            connection.outputStream.use { it.write(bodyBytes) }

            if (connection.responseCode == 200) {
                connection.inputStream.readBytes()
            } else {
                val err = connection.errorStream?.readBytes()?.decodeToString() ?: ""
                XposedBridge.log("SPOOF-EXPERIMENT: Server error ${connection.responseCode}: $err")
                null
            }
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-EXPERIMENT: Network error: ${e.message}")
            null
        }
    }
}
