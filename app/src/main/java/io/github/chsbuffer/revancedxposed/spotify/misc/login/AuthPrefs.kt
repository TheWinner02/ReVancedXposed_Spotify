package io.github.chsbuffer.revancedxposed.spotify.misc.login

import de.robv.android.xposed.XSharedPreferences
import android.content.Context
import java.io.File
import androidx.core.content.edit
import de.robv.android.xposed.XposedBridge

object AuthPrefs {
    const val PREFS_NAME = "spotify_auth_prefs"
    const val KEY_SP_DC = "sp_dc"
    // Il package name del tuo MODULO
    const val MODULE_PKG = "io.github.chsbuffer.revancedxposed"

    // 1. Salvataggio (dentro l'Activity del modulo)
    fun saveToken(context: Context, token: String) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SP_DC, token).apply()

            // LOG DI DEBUG
            XposedBridge.log("AUTH-DEBUG: Token salvato nelle SharedPreferences: ${token.take(5)}***")

            val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
                XposedBridge.log("AUTH-DEBUG: Permessi file impostati a leggibili")
            }
        }.onFailure {
            XposedBridge.log("AUTH-DEBUG: Errore durante il salvataggio: ${it.message}")
        }
    }

    fun getSavedToken(): String? {
        val prefs = XSharedPreferences(MODULE_PKG, PREFS_NAME)
        if (!prefs.file.exists()) {
            XposedBridge.log("AUTH-DEBUG: Il file delle preferenze non esiste in ${prefs.file.absolutePath}")
            return null
        }
        prefs.reload()
        val token = prefs.getString(KEY_SP_DC, null)
        XposedBridge.log("AUTH-DEBUG: Tentativo lettura token... Esito: ${if (token != null) "Trovato" else "NULL"}")
        return token
    }
}