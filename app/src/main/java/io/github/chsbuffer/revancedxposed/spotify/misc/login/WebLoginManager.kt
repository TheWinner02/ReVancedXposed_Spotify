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
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
        }

        webView.removeJavascriptInterface("searchBoxJavaBridge_")
        webView.removeJavascriptInterface("accessibility")
        webView.removeJavascriptInterface("accessibilityTraversal")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
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