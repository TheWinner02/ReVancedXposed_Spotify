package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.Context
import de.robv.android.xposed.XSharedPreferences
import java.io.File

object AuthPrefs {
    const val PREFS_NAME = "spotify_auth_prefs"
    const val KEY_SP_DC = "sp_dc"
    const val MODULE_PKG = "io.github.chsbuffer.revancedxposed"

    // Salvataggio (verrà chiamato dalla WebView dentro Spotify)
    fun saveToken(context: Context, token: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SP_DC, token).apply()

        // Rendiamo il file leggibile per il processo Xposed
        runCatching {
            val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")
            if (prefsFile.exists()) prefsFile.setReadable(true, false)
        }
    }

    // Lettura (chiamata all'avvio dell'app)
    fun getSavedToken(): String? {
        val prefs = XSharedPreferences(MODULE_PKG, PREFS_NAME)
        prefs.makeWorldReadable()
        prefs.reload()
        return prefs.getString(KEY_SP_DC, null)
    }
}