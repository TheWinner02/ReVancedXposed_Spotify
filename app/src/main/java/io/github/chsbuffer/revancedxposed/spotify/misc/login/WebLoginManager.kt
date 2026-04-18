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

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()

                // Se tenta di aprire link esterni o deep link nativi, blocchiamo e restiamo nel web
                if (url.startsWith("spotify:") || url.contains("play.google.com") || url.contains("android-app://")) {
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Fondamentale: assicuriamoci che i cookie siano sincronizzati
                CookieManager.getInstance().flush()

                val cookies = CookieManager.getInstance().getCookie(url)

                if (cookies != null && cookies.contains("sp_dc=")) {
                    val spDc = cookies.split(";")
                        .map { it.trim() }
                        .find { it.startsWith("sp_dc=") }
                        ?.substringAfter("=")

                    if (!spDc.isNullOrBlank()) {
                        XposedBridge.log("SPOOF: Token catturato con successo: ${spDc.take(10)}...")
                        AuthPrefs.saveToken(activity, spDc)

                        // Chiudiamo il dialogo prima di killare l'app
                        dialog.dismiss()

                        // TRUCCO PRO: Diamo un piccolo delay per permettere il salvataggio
                        webView.postDelayed({
                            activity.finishAffinity()
                            // Opzionale: System.exit(0) se vuoi un restart brutale ma pulito
                        }, 500)
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