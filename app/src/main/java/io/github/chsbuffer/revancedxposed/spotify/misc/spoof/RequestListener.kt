package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import app.revanced.extension.shared.Logger
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayInputStream
import java.io.IOException

@OptIn(ExperimentalSerializationApi::class)
class RequestListener(port: Int) : NanoHTTPD(port) {
    init {
        try {
            start()
            Logger.printInfo { "Launched request listener on port $port" }
        } catch (e: IOException) {
            Logger.printException({ "Failed to start request listener on port $port" }, e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri != "/v1/clienttoken") {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/x-protobuf", null)
        }

        Logger.printInfo { "Serving request for URI: $uri" }
        
        val inputStream = session.inputStream
        val contentLength = session.headers["content-length"]?.toLong() ?: 0L
        
        // NanoHTTPD's inputStream might need careful handling for fixed length
        val limitedInputStream = object : java.io.FilterInputStream(inputStream) {
            private var remaining = contentLength
            override fun read(): Int {
                if (remaining <= 0) return -1
                val result = super.read()
                if (result != -1) remaining--
                return result
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val result = super.read(b, off, minOf(len.toLong(), remaining).toInt())
                if (result != -1) remaining -= result.toLong()
                return result
            }
            override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
        }

        val clientTokenResponse = IosClientTokenService.serveClientTokenRequest(limitedInputStream)
        return if (clientTokenResponse != null) {
            val bytes = ProtoBuf.encodeToByteArray(clientTokenResponse)
            newFixedLengthResponse(Response.Status.OK, "application/x-protobuf", ByteArrayInputStream(bytes), bytes.size.toLong())
        } else {
            Logger.printInfo { "Failed to serve client token request" }
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/x-protobuf", null)
        }
    }
}
