package io.github.chsbuffer.revancedxposed.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import de.robv.android.xposed.XposedBridge
import io.github.chsbuffer.revancedxposed.spotify.misc.login.AuthPrefs

class LoginActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                XposedBridge.log("LOGIN-UI: Pagina caricata: $url")
                val cookies = CookieManager.getInstance().getCookie(url)
                XposedBridge.log("LOGIN-UI: Cookie totali trovati: ${cookies.length} caratteri")
                if (cookies != null && cookies.contains("sp_dc=")) {
                    val token = cookies.split(";")
                        .find { it.trim().startsWith("sp_dc=") }
                        ?.substringAfter("=")

                    if (token != null) {
                        XposedBridge.log("LOGIN-UI: Token sp_dc intercettato!")
                        AuthPrefs.saveToken(this@LoginActivity, token)
                        // Qui potresti aggiungere un log o un toast
                        finish()
                    }
                }
            }
        }
        webView.loadUrl("https://accounts.spotify.com/it/login")
    }
}