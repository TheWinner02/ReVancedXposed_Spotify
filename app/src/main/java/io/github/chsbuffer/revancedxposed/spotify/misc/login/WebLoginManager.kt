package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.*
import android.widget.LinearLayout
import de.robv.android.xposed.XposedBridge

object WebLoginManager {

    @SuppressLint("SetJavaScriptEnabled")
    fun showLoginOverlay(activity: Activity) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val webView = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                setSupportMultipleWindows(true)
                // Usiamo un User-Agent ancora più recente per evitare sospetti
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UD1A.230805.019) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.40 Mobile Safari/537.36"
            }
        }

        // Pulizia pre-caricamento per evitare che vecchi dati segnalino l'app come moddata
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()

        webView.webViewClient = object : WebViewClient() {

            // TRUCCO: Invece di intercettare ogni singola risorsa (che è pesante),
            // ci assicuriamo che la navigazione principale sia pulita.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()

                // Se rileviamo che Spotify sta cercando di forzare un'autenticazione nativa
                // (che fallirebbe su oPatch), forziamo il caricamento web puro
                return url.contains("spotify://") || url.contains("android-app://")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val cookies = CookieManager.getInstance().getCookie(url)

                // Cerchiamo sp_dc (il token di sessione) o sp_key
                if (cookies != null && (cookies.contains("sp_dc=") || cookies.contains("sp_key="))) {
                    val spDc = cookies.split(";")
                        .find { it.trim().startsWith("sp_dc=") }
                        ?.substringAfter("=")

                    if (spDc != null) {
                        XposedBridge.log("SPOOF: Token catturato con successo!")
                        AuthPrefs.saveToken(activity, spDc)
                        dialog.dismiss()

                        // Invece di recreate(), forziamo la chiusura e riapertura manuale
                        // per pulire i processi nativi "congelati"
                        activity.finishAffinity()
                    }
                }
            }
        }

        layout.addView(webView)
        dialog.setContentView(layout)

        // Se il login standard fallisce, usa questo URL che forza la versione Web "Legacy"
        // molto più facile da bypassare rispetto alla nuova versione con Google Auth integrato.
        webView.loadUrl("https://accounts.spotify.com/it/login?continue=https%3A%2F%2Fopen.spotify.com%2F")

        dialog.show()
    }
}