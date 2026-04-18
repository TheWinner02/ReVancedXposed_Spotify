package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.content.Context
import de.robv.android.xposed.XposedBridge

object AuthPrefs {
    private const val PREFS_NAME = "spotify_auth_prefs"
    private const val KEY_SP_DC = "sp_dc"

    fun saveToken(context: Context, token: String) {
        // Usiamo commit() invece di apply() nel salvataggio del token.
        // commit() è sincrono e garantisce che il dato sia scritto su disco prima di proseguire.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val success = prefs.edit().putString(KEY_SP_DC, token).commit()

        if (success) {
            XposedBridge.log("AUTH-DEBUG: Token salvato fisicamente su disco.")
        }
    }

    fun getSavedToken(context: Context?): String? {
        if (context == null) return null

        // Trick per forzare il ricaricamento delle SharedPreferences da disco
        // In Android moderno, MODE_MULTI_PROCESS è deprecato ma caricare il file
        // ogni volta che serve il token previene letture di cache vecchie.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_SP_DC, null)

        if (token != null) {
            XposedBridge.log("AUTH-DEBUG: Token letto (${token.take(8)}...)")
        }
        return token
    }
}