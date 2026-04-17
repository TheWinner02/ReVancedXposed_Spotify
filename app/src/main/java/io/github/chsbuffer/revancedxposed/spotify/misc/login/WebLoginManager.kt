package io.github.chsbuffer.revancedxposed.spotify.misc.login

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.*
import android.widget.LinearLayout

object WebLoginManager {

    @SuppressLint("SetJavaScriptEnabled")
    fun showLoginOverlay(activity: Activity) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val webView = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                // User-Agent Pixel 8 per ingannare Google
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Rimuoviamo l'header che identifica l'app patchata
                val headers = HashMap(request.requestHeaders)
                headers.remove("X-Requested-With")
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Iniezione JS anti-bot
                view?.evaluateJavascript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})", null)

                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies?.contains("sp_dc=") == true) {
                    val token = cookies.split(";").find { it.trim().startsWith("sp_dc=") }?.substringAfter("=")
                    if (token != null) {
                        AuthPrefs.saveToken(activity, token)
                        dialog.dismiss()
                        activity.recreate()
                    }
                }
            }
        }

        layout.addView(webView)
        dialog.setContentView(layout)
        dialog.show()
        webView.loadUrl("https://accounts.spotify.com/login") // URL diretto
    }
}