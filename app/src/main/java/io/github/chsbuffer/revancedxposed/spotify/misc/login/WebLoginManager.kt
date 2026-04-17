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
        val context = activity
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val webView = WebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UD1A.230805.019) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.64 Mobile Safari/537.36"
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
        }

        webView.removeJavascriptInterface("searchBoxJavaBridge_")
        webView.removeJavascriptInterface("accessibility")
        webView.removeJavascriptInterface("accessibilityTraversal")

        webView.webViewClient = object : WebViewClient() {
            // Questo header viene rimosso automaticamente dal sistema se lo StealthMode
            // intercetta correttamente le chiamate del framework, ma per sicurezza
            // aggiungiamo questo controllo sulla navigazione:

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Se Google o Spotify provano a fare un redirect sospetto,
                // forziamo il caricamento manuale per pulire la sessione
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Nascondiamo le tracce di automazione tramite iniezione JS
                view?.evaluateJavascript(
                    "(function() { " +
                            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});" +
                            "window.chrome = { runtime: {} };" +
                            "})();", null
                )

                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies?.contains("sp_dc=") == true) {
                    val token = cookies.split(";")
                        .find { it.trim().startsWith("sp_dc=") }
                        ?.substringAfter("=")

                    if (token != null) {
                        AuthPrefs.saveToken(activity, token) // Salva nel pacchetto di Spotify
                        dialog.dismiss()
                        activity.recreate()
                    }
                }
            }
        }

        layout.addView(webView)
        dialog.setContentView(layout)
        dialog.show()

        webView.loadUrl("https://accounts.spotify.com/login")
    }
}