package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.Context
import de.robv.android.xposed.XposedBridge

object AuthPrefs {
    const val PREFS_NAME = "spotify_auth_prefs"
    const val KEY_SP_DC = "sp_dc"

    // Salvataggio: usa le preferenze locali di Spotify
    fun saveToken(context: Context, token: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SP_DC, token).apply()
    }

    // Lettura: legge direttamente dalle preferenze locali
    fun getSavedToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_SP_DC, null)

        // DEBUG LOG
        if (token != null) {
            XposedBridge.log("AUTH-DEBUG: Token letto con successo dal pacchetto Spotify (${token.take(8)}...)")
        } else {
            XposedBridge.log("AUTH-DEBUG: Nessun token trovato nelle preferenze locali.")
        }
        return token
    }
}