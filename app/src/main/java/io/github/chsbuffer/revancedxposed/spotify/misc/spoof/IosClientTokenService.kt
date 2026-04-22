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
            val builderClass = OkHttpClient::class.java.classLoader?.loadClass("okhttp3.Request\$Builder")
                               ?: Class.forName("okhttp3.Request\$Builder")
            val requestClass = OkHttpClient::class.java.classLoader?.loadClass("okhttp3.Request")
                               ?: Class.forName("okhttp3.Request")
            val requestBodyClass = OkHttpClient::class.java.classLoader?.loadClass("okhttp3.RequestBody")
                                   ?: Class.forName("okhttp3.RequestBody")
            
            val builder = builderClass.getDeclaredConstructor().newInstance()
            
            val allMethods = builderClass.methods
            XposedBridge.log("SPOOF-PROXY: Analisi ${allMethods.size} metodi in Request\$Builder")
            
            // Dalla scansione dei log, i nomi sono:
            // a(String, String) -> header
            // d(String, String) -> header (probabilmente addHeader)
            // e(String, RequestBody) -> method
            // f(String) -> url (probabilmente)
            // g(String) -> url (probabilmente)
            // c() -> build (unico senza parametri che ritorna void/Object? No, deve ritornare Request)
            
            // Cerchiamo URL: metodo che accetta String e NON ritorna void (ritorna il Builder per concatenare)
            val urlMethod = allMethods.find { m ->
                m.parameterTypes.size == 1 && 
                m.parameterTypes[0] == String::class.java && 
                m.returnType != Void.TYPE &&
                (m.name == "f" || m.name == "g" || m.name == "url")
            } ?: allMethods.find { it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
              ?: throw NoSuchMethodException("URL Method not found")

            // Cerchiamo HEADER: metodo che accetta (String, String) e NON ritorna void
            val headerMethod = allMethods.find { m ->
                m.parameterTypes.size == 2 && 
                m.parameterTypes[0] == String::class.java && 
                m.parameterTypes[1] == String::class.java &&
                m.returnType != Void.TYPE &&
                (m.name == "a" || m.name == "d" || m.name == "header")
            } ?: allMethods.find { it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == String::class.java }
              ?: throw NoSuchMethodException("Header Method not found")
            
            // Cerchiamo METHOD (POST): metodo che accetta (String, RequestBody)
            val genericMethod = allMethods.find { m ->
                m.parameterTypes.size == 2 && 
                m.parameterTypes[0] == String::class.java && 
                (m.parameterTypes[1].name.contains("RequestBody") || requestBodyClass.isAssignableFrom(m.parameterTypes[1]))
            } ?: throw NoSuchMethodException("Generic Method (POST) not found")

            // Cerchiamo BUILD: metodo senza parametri che restituisce l'oggetto Request
            // NOTA: Se l'offuscatore ha cambiato il ritorno in 'void' o 'Object', cerchiamo per esclusione
            val buildMethod = allMethods.find { m ->
                m.parameterTypes.isEmpty() && 
                (m.returnType == requestClass || m.returnType.name.contains("Request")) &&
                !m.name.contains("getClass")
            } ?: allMethods.find { it.parameterTypes.isEmpty() && it.name == "c" } // Sospetto 'c' dai log
              ?: allMethods.find { it.name == "build" }
              ?: throw NoSuchMethodException("Build Method not found")

            XposedBridge.log("SPOOF-PROXY: Metodi selezionati: URL=${urlMethod.name}, HEADER=${headerMethod.name}, POST=${genericMethod.name}, BUILD=${buildMethod.name}")

            urlMethod.invoke(builder, "https://clienttoken.spotify.com/v1/clienttoken")
            genericMethod.invoke(builder, "POST", body)
            headerMethod.invoke(builder, "Content-Type", "application/x-protobuf")
            headerMethod.invoke(builder, "Accept", "application/x-protobuf")
            headerMethod.invoke(builder, "User-Agent", IOS_USER_AGENT)
            
            val request = buildMethod.invoke(builder) as Request

            urlMethod.invoke(builder, "https://clienttoken.spotify.com/v1/clienttoken")
            genericMethod.invoke(builder, "POST", body)
            headerMethod.invoke(builder, "Content-Type", "application/x-protobuf")
            headerMethod.invoke(builder, "Accept", "application/x-protobuf")
            headerMethod.invoke(builder, "User-Agent", IOS_USER_AGENT)
            
            val finalRequest = buildMethod.invoke(builder) as Request

            okHttpClient.newCall(finalRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    XposedBridge.log("SPOOF-PROXY: Errore HTTP da Spotify: ${response.code}")
                    return null
                }
                val responseBody = response.body
                ProtoBuf.decodeFromByteArray<ClientTokenResponse>(responseBody.bytes())
            }
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-PROXY: Errore riflessione critica OkHttp: ${e.message}")
            null
        }
    }
}
