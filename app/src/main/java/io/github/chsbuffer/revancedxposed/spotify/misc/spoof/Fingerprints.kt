package io.github.chsbuffer.revancedxposed.spotify.misc.spoof

/**
 * Impronte digitali estratte dalle sorgenti ReVanced Pro.
 * Queste stringhe e pattern permettono di localizzare i metodi di sicurezza
 * di Spotify in modo affidabile tra diverse versioni dell'app.
 */
object SpoofFingerprints {
    // Il metodo di protezione nativa che fa scattare la schermata nera (Integrity Verification)
    const val INTEGRITY_PROTECTION_PACKAGE = "com/spotify/connectivity"
    const val INTEGRITY_PROTECTION_METHOD = "run"
    
    // Pattern per identificare il ritorno della piattaforma interna
    const val PLATFORM_IDENTIFIER_STRING = "android"
    const val PLATFORM_IOS_TARGET = "ios"
    
    // Identità Master V8.8.84 (Gold Standard)
    const val MASTER_CLIENT_ID = "58bd3c95768941ea9eb4350aaa033eb3"
    const val MASTER_USER_AGENT = "Spotify/8.8.84 iOS/17.7.2 (iPhone16,1)"
    const val MASTER_DEVICE_ID = "2A084F20-1307-3AE0-83C8-AE5CA4AB5CD0"
    const val MASTER_VERSION = "iphone-8.8.84.502"
    
    // Firma Spotify Originale (Hex) per lo Signature Spoof
    const val SPOTIFY_ORIGINAL_SHA = "6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd"
}
