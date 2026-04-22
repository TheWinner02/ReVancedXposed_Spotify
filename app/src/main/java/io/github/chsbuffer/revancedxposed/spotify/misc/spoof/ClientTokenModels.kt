package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Suppress("unused")
@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class ClientTokenRequestType {
    @ProtoNumber(0) REQUEST_UNKNOWN,
    @ProtoNumber(1) REQUEST_CLIENT_DATA_REQUEST,
    @ProtoNumber(2) REQUEST_CHALLENGE_ANSWERS_REQUEST
}

@Suppress("unused")
@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class ClientTokenResponseType {
    @ProtoNumber(0) RESPONSE_UNKNOWN,
    @ProtoNumber(1) RESPONSE_GRANTED_TOKEN_RESPONSE,
    @ProtoNumber(2) RESPONSE_CHALLENGES_RESPONSE
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class NativeIOSData(
    @ProtoNumber(1) val userInterfaceIdiom: Int = 0,
    @ProtoNumber(2) val targetIphoneSimulator: Boolean = false,
    @ProtoNumber(3) val hwMachine: String = "",
    @ProtoNumber(4) val systemVersion: String = "",
    @ProtoNumber(5) val simulatorModelIdentifier: String = ""
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PlatformSpecificData(
    @ProtoNumber(2) val ios: NativeIOSData? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConnectivitySdkData(
    @ProtoNumber(1) val platformSpecificData: PlatformSpecificData? = null,
    @ProtoNumber(2) val deviceId: String = ""
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClientDataRequest(
    @ProtoNumber(1) val clientVersion: String = "",
    @ProtoNumber(2) val clientId: String = "",
    @ProtoNumber(3) val connectivitySdkData: ConnectivitySdkData? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClientTokenRequest(
    @ProtoNumber(1) val requestType: ClientTokenRequestType = ClientTokenRequestType.REQUEST_UNKNOWN,
    @ProtoNumber(2) val clientData: ClientDataRequest? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TokenDomain(
    @ProtoNumber(1) val domain: String = ""
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GrantedTokenResponse(
    @ProtoNumber(1) val token: String = "",
    @ProtoNumber(2) val expiresAfterSeconds: Int = 0,
    @ProtoNumber(3) val refreshAfterSeconds: Int = 0,
    @ProtoNumber(4) val domains: List<TokenDomain> = emptyList()
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClientTokenResponse(
    @ProtoNumber(1) val responseType: ClientTokenResponseType = ClientTokenResponseType.RESPONSE_UNKNOWN,
    @ProtoNumber(2) val grantedToken: GrantedTokenResponse? = null
)
