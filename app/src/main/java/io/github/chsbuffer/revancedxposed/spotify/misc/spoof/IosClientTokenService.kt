package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

import de.robv.android.xposed.XposedBridge
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier

/**
 * ESPERIMENTO: PURE-ANDROID RELAY
 * In questo servizio, NON facciamo nessuno spoofing iOS.
 * Prendiamo i byte Android originali e li inoltriamo tali e quali a Spotify.
 */
object IosClientTokenService {

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

    fun serveClientTokenRequest(inputStream: InputStream, originalHeaders: Map<String, String>): ByteArray? {
        return try {
            val bytes = inputStream.readBytes()
            XposedBridge.log("SPOOF-EXPERIMENT: [RELAY] Ricevuti byte Android (${bytes.size} bytes)")
            XposedBridge.log("SPOOF-EXPERIMENT: [RELAY] Hex: ${bytes.toHexString()}")

            // Inoltriamo il pacchetto ORIGINALE senza trasformazioni
            val response = requestClientTokenRaw(bytes, originalHeaders)
            
            if (response != null) {
                XposedBridge.log("SPOOF-EXPERIMENT: [RELAY] Risposta server ricevuta (${response.size} bytes)")
            }
            return response
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-EXPERIMENT: [RELAY] Errore critico: ${e.message}")
            null
        }
    }

    private fun requestClientTokenRaw(bodyBytes: ByteArray, originalHeaders: Map<String, String>): ByteArray? {
        val host = "clienttoken.spotify.com"
        return try {
            val url = URL("https://$host/v1/clienttoken")
            val connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            
            // Bypass validazione per test
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }

            // Copiamo gli header originali Android (NIENTE iOS qui)
            originalHeaders.forEach { (key, value) ->
                if (!listOf("host", "content-length", "connection").contains(key.lowercase())) {
                    connection.setRequestProperty(key, value)
                }
            }

            connection.outputStream.use { it.write(bodyBytes) }

            if (connection.responseCode == 200) {
                connection.inputStream.readBytes()
            } else {
                val err = connection.errorStream?.readBytes()?.decodeToString() ?: ""
                XposedBridge.log("SPOOF-EXPERIMENT: [RELAY] Server Error ${connection.responseCode}: $err")
                null
            }
        } catch (e: Exception) {
            XposedBridge.log("SPOOF-EXPERIMENT: [RELAY] Network Error: ${e.message}")
            null
        }
    }
}
