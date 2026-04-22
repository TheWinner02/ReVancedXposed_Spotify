package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import de.robv.android.xposed.XposedBridge
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import java.io.InputStream

@OptIn(ExperimentalSerializationApi::class)
object IosClientTokenService {
    private const val IOS_CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3"
    private const val CLIENT_VERSION = "iphone-9.0.58"
    private const val SYSTEM_VERSION = "17.7.2"
    private const val HARDWARE_MACHINE = "iPhone16,1"

    private const val IOS_USER_AGENT = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"

    private val okHttpClient = OkHttpClient()

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
        // ... (IL TUO CODICE QUI RIMANE IDENTICO) ...
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
            val loader = OkHttpClient::class.java.classLoader
            val builderClass = loader?.loadClass("okhttp3.Request\$Builder") ?: Class.forName("okhttp3.Request\$Builder")
            val requestClass = loader?.loadClass("okhttp3.Request") ?: Class.forName("okhttp3.Request")
            val requestBodyClass = loader?.loadClass("okhttp3.RequestBody") ?: Class.forName("okhttp3.RequestBody")
            val mediaTypeClass = loader?.loadClass("okhttp3.MediaType") ?: Class.forName("okhttp3.MediaType")
            val mediaTypeCompanionClass = loader?.loadClass("okhttp3.MediaType\$Companion") ?: Class.forName("okhttp3.MediaType\$Companion")
            val callClass = loader?.loadClass("okhttp3.Call") ?: Class.forName("okhttp3.Call")
            val responseClass = loader?.loadClass("okhttp3.Response") ?: Class.forName("okhttp3.Response")
            val responseBodyClass = loader?.loadClass("okhttp3.ResponseBody") ?: Class.forName("okhttp3.ResponseBody")

            val mediaTypeField = mediaTypeClass.getDeclaredField("e")
            val mediaTypeCompanion = mediaTypeField.get(null)
            val parseMethod = mediaTypeCompanionClass.getDeclaredMethod("b", String::class.java)
            val mediaType = parseMethod.invoke(mediaTypeCompanion, "application/x-protobuf")

            val createBodyMethod = requestBodyClass.getDeclaredMethod("c", mediaTypeClass, ByteArray::class.java)
            val body = createBodyMethod.invoke(null, mediaType, bodyBytes)

            val builder = builderClass.getDeclaredConstructor().newInstance()

            val urlMethod = builderClass.getDeclaredMethod("g", String::class.java)
            urlMethod.invoke(builder, "https://clienttoken.spotify.com/v1/clienttoken")

            val methodMethod = builderClass.getDeclaredMethod("e", String::class.java, requestBodyClass)
            methodMethod.invoke(builder, "POST", body)

            val headerMethod = builderClass.getDeclaredMethod("a", String::class.java, String::class.java)
            headerMethod.invoke(builder, "Content-Type", "application/x-protobuf")
            headerMethod.invoke(builder, "Accept", "application/x-protobuf")
            headerMethod.invoke(builder, "User-Agent", IOS_USER_AGENT)
            headerMethod.invoke(builder, "X-Client-Id", IOS_CLIENT_ID)
            headerMethod.invoke(builder, "App-Platform", "ios")

            val requestConstructor = requestClass.getDeclaredConstructor(builderClass)
            val request = requestConstructor.newInstance(builder)

            val newCallMethod = OkHttpClient::class.java.getDeclaredMethod("a", requestClass)
            val call = newCallMethod.invoke(okHttpClient, request)

            val executeMethod = callClass.getDeclaredMethod("d")
            val response = executeMethod.invoke(call)

            val bodyField = responseClass.getDeclaredField("g")
            val responseBody = bodyField.get(response)

            val bytesMethod = responseBodyClass.getDeclaredMethod("d")
            // RESTITUISCE I BYTE CRUDI INVECE DI DECODIFICARLI
            bytesMethod.invoke(responseBody) as ByteArray

        } catch (e: Exception) {
            XposedBridge.log("SPOOF-PROXY: Errore riflessione fisica OkHttp: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}