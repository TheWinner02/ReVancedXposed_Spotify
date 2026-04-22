package io.github.chsbuffer.revancedxposed.spotify.misc.spoof;

import okhttp3.MediaType;
import okhttp3.RequestBody;

public class OkHttpHelper {
    public static MediaType parseMediaType(String type) {
        // OkHttp 3 uses MediaType.parse(String)
        // OkHttp 4+ uses MediaType.get(String) or Companion.parse(String)
        // We try parse first which is common across many versions.
        return MediaType.parse(type);
    }

    public static RequestBody createRequestBody(MediaType type, byte[] bytes) {
        // OkHttp 3 uses RequestBody.create(MediaType, byte[])
        // OkHttp 4+ uses RequestBody.create(byte[], MediaType) in Java or Companion.create in Kotlin
        // We use the (MediaType, byte[]) signature which is the original one.
        return RequestBody.create(type, bytes);
    }
}
