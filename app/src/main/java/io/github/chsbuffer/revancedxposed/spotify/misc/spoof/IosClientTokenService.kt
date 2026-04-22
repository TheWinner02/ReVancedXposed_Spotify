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
            // Tentiamo di caricare la classe tramite il ClassLoader di sistema/app, non quello del modulo
            val builderClass = OkHttpClient::class.java.classLoader?.loadClass("okhttp3.Request\$Builder") 
                               ?: Class.forName("okhttp3.Request\$Builder")
            val requestBodyClass = OkHttpClient::class.java.classLoader?.loadClass("okhttp3.RequestBody")
                                   ?: Class.forName("okhttp3.RequestBody")
            
            val builder = builderClass.getDeclaredConstructor().newInstance()
            
            // In alcune versioni R8/ProGuard di Spotify, i nomi sono offuscati (a, b, c...)
            // Cerchiamo i metodi basandoci sulla firma dei parametri invece che sul nome
            val urlMethod = builderClass.methods.find { it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java && it.returnType == builderClass }
                ?: builderClass.methods.find { it.name == "url" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
                ?: throw NoSuchMethodException("url(String) not found")
            
            val postMethod = builderClass.methods.find { it.parameterTypes.size == 1 && requestBodyClass.isAssignableFrom(it.parameterTypes[0]) && it.returnType == builderClass }
                ?: builderClass.methods.find { it.name == "post" && it.parameterTypes.size == 1 && requestBodyClass.isAssignableFrom(it.parameterTypes[0]) }
                ?: throw NoSuchMethodException("post(RequestBody) not found")
                
            val headerMethod = builderClass.methods.find { it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == String::class.java && it.returnType == builderClass }
                ?: builderClass.methods.find { it.name == "header" && it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == String::class.java }
                ?: throw NoSuchMethodException("header(String, String) not found")
                
            val buildMethod = builderClass.methods.find { it.parameterTypes.isEmpty() && it.returnType.name.endsWith(".Request") }
                ?: builderClass.getMethod("build")

            urlMethod.invoke(builder, "https://clienttoken.spotify.com/v1/clienttoken")
            postMethod.invoke(builder, body)
            headerMethod.invoke(builder, "Content-Type", "application/x-protobuf")
            headerMethod.invoke(builder, "Accept", "application/x-protobuf")
            headerMethod.invoke(builder, "User-Agent", IOS_USER_AGENT)
            
            val request = buildMethod.invoke(builder) as Request

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    XposedBridge.log("SPOOF-PROXY: Errore HTTP da Spotify: ${response.code}")
                    return null
                }
                val responseBody = response.body
                ProtoBuf.decodeFromByteArray<ClientTokenResponse>(responseBody.bytes())
            }
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-PROXY: Errore riflessione critica OkHttp: ${e.message}")
            // Loggiamo i metodi disponibili per debug se fallisce
            runCatching {
                val builderClass = Class.forName("okhttp3.Request\$Builder")
                XposedBridge.log("SPOOF-PROXY: Metodi disponibili in Request\$Builder: ${builderClass.methods.map { it.name }.distinct()}")
            }
            null
        }
    }
}
