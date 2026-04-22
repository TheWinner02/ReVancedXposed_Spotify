package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import app.revanced.extension.shared.Logger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
            val request = ProtoBuf.decodeFromByteArray<ClientTokenRequest>(inputStream.readBytes())
            Logger.printInfo { "Request of type: ${request.requestType}" }
            
            val response = getClientTokenResponse(request)
            if (response != null) {
                Logger.printInfo { "Response of type: ${response.responseType}" }
            }
            response
        } catch (e: Exception) {
            Logger.printException({ "Failed to parse request from input stream" }, e)
            null
        }
    }

    private fun getClientTokenResponse(originalRequest: ClientTokenRequest): ClientTokenResponse? {
        var request = originalRequest
        if (request.requestType == ClientTokenRequestType.REQUEST_CLIENT_DATA_REQUEST) {
            Logger.printInfo { "Requesting iOS client token" }
            val deviceId = request.clientData?.connectivitySdkData?.deviceId ?: ""
            request = newIOSClientTokenRequest(deviceId)
        }

        return try {
            requestClientToken(request)
        } catch (e: Exception) {
            Logger.printException({ "Failed to handle request" }, e)
            null
        }
    }

    private fun newIOSClientTokenRequest(deviceId: String): ClientTokenRequest {
        Logger.printInfo { "Creating new iOS client token request with device ID: $deviceId" }

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
        val body = ProtoBuf.encodeToByteArray(clientTokenRequest).toRequestBody("application/x-protobuf".toMediaType())
        val request = Request.Builder()
            .url("https://clienttoken.spotify.com/v1/clienttoken")
            .post(body)
            .header("Content-Type", "application/x-protobuf")
            .header("Accept", "application/x-protobuf")
            .header("User-Agent", IOS_USER_AGENT)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseBody = response.body
            return ProtoBuf.decodeFromByteArray<ClientTokenResponse>(responseBody.bytes())
        }
    }
}
