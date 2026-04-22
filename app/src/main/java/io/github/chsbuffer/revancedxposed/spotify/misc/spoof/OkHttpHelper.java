package io.github.chsbuffer.revancedxposed.spotify.misc.spoof;

import okhttp3.MediaType;
import okhttp3.RequestBody;

public class OkHttpHelper {
    public static MediaType parseMediaType(String type) {
        try {
            // Tentiamo il metodo statico 'get' (OkHttp 4+)
            return (MediaType) MediaType.class.getMethod("get", String.class).invoke(null, type);
        } catch (Exception e) {
            try {
                // Tentiamo il metodo statico 'parse' (OkHttp 3)
                return (MediaType) MediaType.class.getMethod("parse", String.class).invoke(null, type);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    public static RequestBody createRequestBody(MediaType type, byte[] bytes) {
        try {
            // Tentiamo RequestBody.create(MediaType, byte[]) - OkHttp 3 e 4
            return (RequestBody) RequestBody.class.getMethod("create", MediaType.class, byte[].class).invoke(null, type, bytes);
        } catch (Exception e) {
            try {
                // Tentiamo RequestBody.create(byte[], MediaType) - Alcune versioni di OkHttp 4
                return (RequestBody) RequestBody.class.getMethod("create", byte[].class, MediaType.class).invoke(null, bytes, type);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
