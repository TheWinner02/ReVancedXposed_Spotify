package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import de.robv.android.xposed.XposedBridge
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException

class RequestListener(port: Int) : NanoHTTPD(port) {
    init {
        try {
            start()
            XposedBridge.log("SPOOF-PROXY: Server avviato sulla porta $port")
        } catch (e: IOException) {
            XposedBridge.log("SPOOF-PROXY: Errore avvio server: ${e.message}")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        // Accettiamo solo l'endpoint del token (Strategia ReVanced Pro)
        if (!session.uri.contains("clienttoken")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }

        XposedBridge.log("SPOOF-PROXY: Gestione richiesta per ${session.uri}")

        val inputStream = session.inputStream
        val contentLength = session.headers["content-length"]?.toLong() ?: 0L

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

        // Passiamo la richiesta al servizio di spoofing iOS
        val responseBytes = IosClientTokenService.serveClientTokenRequest(limitedInputStream, session.headers)

        return if (responseBytes != null) {
            newFixedLengthResponse(
                Response.Status.OK,
                "application/x-protobuf",
                ByteArrayInputStream(responseBytes),
                responseBytes.size.toLong()
            )
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/x-protobuf", null)
        }
    }
}
