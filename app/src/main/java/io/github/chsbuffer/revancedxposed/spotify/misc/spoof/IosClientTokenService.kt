package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import de.robv.android.xposed.XposedBridge
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream

@OptIn(ExperimentalSerializationApi::class)
object IosClientTokenService {
    private const val IOS_CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3"
    private const val CLIENT_VERSION = "iphone-9.0.58"
    private const val SYSTEM_VERSION = "17.7.2"
    private const val HARDWARE_MACHINE = "iPhone16,1"

    private const val IOS_USER_AGENT = "Spotify/9.0.58 iOS/17.7.2 (iPhone16,1)"

    private val okHttpClient = OkHttpClient()

    fun serveClientTokenRequest(inputStream: InputStream): ClientTokenResponse? {
        return try {
            val bytes = inputStream.readBytes()
            XposedBridge.log("SPOOF-PROXY: Ricevuta richiesta Protobuf (${bytes.size} bytes)")
            val request = ProtoBuf.decodeFromByteArray<ClientTokenRequest>(bytes)
            XposedBridge.log("SPOOF-PROXY: Tipo richiesta originale: ${request.requestType}")
            
            val response = getClientTokenResponse(request)
            if (response != null) {
                XposedBridge.log("SPOOF-PROXY: Risposta iOS generata con successo (Tipo: ${response.responseType})")
            }
            response
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-PROXY: Errore nel servire la richiesta clienttoken: ${e.message}")
            null
        }
    }

    private fun getClientTokenResponse(originalRequest: ClientTokenRequest): ClientTokenResponse? {
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

        val iosData = NativeIOSData(
            hwMachine = HARDWARE_MACHINE,
            systemVersion = SYSTEM_VERSION
        )

        val platformData = PlatformSpecificData(
            ios = iosData
        )

        val sdkData = ConnectivitySdkData(
            deviceId = deviceId,
            platformSpecificData = platformData
        )

        val clientData = ClientDataRequest(
            clientId = IOS_CLIENT_ID,
            clientVersion = CLIENT_VERSION,
            connectivitySdkData = sdkData
        )

        return ClientTokenRequest(
            requestType = ClientTokenRequestType.REQUEST_CLIENT_DATA_REQUEST,
            clientData = clientData
        )
    }

    private fun requestClientToken(clientTokenRequest: ClientTokenRequest): ClientTokenResponse? {
        val bodyBytes = ProtoBuf.encodeToByteArray(clientTokenRequest)
        val mediaType = OkHttpHelper.parseMediaType("application/x-protobuf")
        val body = OkHttpHelper.createRequestBody(mediaType, bodyBytes)
        
        return try {
            // Usiamo la riflessione per evitare NoSuchMethodError su url(), post(), header() e build()
            // causati dalle differenze tra OkHttp 3 e 4/5 (valutati a runtime)
            val builderClass = Class.forName("okhttp3.Request\$Builder")
            val requestBodyClass = Class.forName("okhttp3.RequestBody")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            
            // builder.url(String)
            builderClass.getMethod("url", String::class.java).invoke(builder, "https://clienttoken.spotify.com/v1/clienttoken")
            
            // builder.post(RequestBody)
            builderClass.getMethod("post", requestBodyClass).invoke(builder, body)
            
            // builder.header(String, String)
            val headerMethod = builderClass.getMethod("header", String::class.java, String::class.java)
            headerMethod.invoke(builder, "Content-Type", "application/x-protobuf")
            headerMethod.invoke(builder, "Accept", "application/x-protobuf")
            headerMethod.invoke(builder, "User-Agent", IOS_USER_AGENT)
            
            // builder.build()
            val request = builderClass.getMethod("build").invoke(builder) as Request

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    XposedBridge.log("SPOOF-PROXY: Errore HTTP da Spotify: ${response.code}")
                    return null
                }
                val responseBody = response.body
                ProtoBuf.decodeFromByteArray<ClientTokenResponse>(responseBody.bytes())
            }
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-PROXY: Errore riflessione Request.Builder: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}
