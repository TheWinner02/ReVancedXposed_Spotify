package io.github.chsbuffer.revancedxposed.spotify.misc.login

import de.robv.android.xposed.XSharedPreferences
import android.content.Context
import java.io.File
import androidx.core.content.edit

object AuthPrefs {
    const val PREFS_NAME = "spotify_auth_prefs"
    const val KEY_SP_DC = "sp_dc"
    // Il package name del tuo MODULO
    const val MODULE_PKG = "io.github.chsbuffer.revancedxposed"

    // 1. Salvataggio (dentro l'Activity del modulo)
    fun saveToken(context: Context, token: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_SP_DC, token) }

        // Trucco per Android nuovi: rendiamo il file leggibile manualmente
        // solo dopo averlo scritto
        runCatching {
            val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
            }
        }
    }

    // 2. Lettura (dentro Spotify via Xposed)
    fun getSavedToken(): String? {
        // XSharedPreferences gestisce internamente la lettura dal modulo
        val prefs = XSharedPreferences(MODULE_PKG, PREFS_NAME)
        prefs.makeWorldReadable() // Anche se deprecato, LSPosed lo usa internamente in modo sicuro
        prefs.reload() // Forza la lettura dal disco
        return prefs.getString(KEY_SP_DC, null)
    }
}