package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.data.tidal.TidalLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOGIN_URL = "https://listen.tidal.com/login"

private val TIDAL_COOKIE_URLS = listOf(
    "https://listen.tidal.com",
    "https://login.tidal.com",
    "https://tidal.com",
)

private val LOGIN_HOST_SUFFIXES = listOf(
    "tidal.com", "google.com", "gstatic.com", "facebook.com", "apple.com", "twitter.com",
)

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

private fun hostMatches(uri: Uri?, suffixes: List<String>): Boolean {
    if (uri?.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return suffixes.any { host == it || host.endsWith(".$it") }
}

private fun isLoginHost(uri: Uri?) = hostMatches(uri, LOGIN_HOST_SUFFIXES)

/** Clears stored Tidal session cookies and cached web storage. */
fun clearTidalWebSession() {
    val cookies = CookieManager.getInstance()
    for (url in TIDAL_COOKIE_URLS) {
        val names = cookies.getCookie(url)?.split(";")
            ?.map { it.substringBefore("=").trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        for (name in names) {
            val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
            cookies.setCookie(url, expired)
            cookies.setCookie(url, "$expired; Domain=.tidal.com")
        }
    }
    cookies.flush()
}

private const val HOOK_JS = """
(function() {
  if (window.__plonkerHooked) return;
  window.__plonkerHooked = true;
  function checkHeader(name, value) {
    try {
      if (name && String(name).toLowerCase() === 'authorization' && value && String(value).indexOf('Bearer ') === 0) {
        window.PlonkerCapture.onToken(String(value).substring(7).trim());
      }
    } catch(e) {}
  }
  var origSet = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
    checkHeader(name, value);
    return origSet.apply(this, arguments);
  };
  var origFetch = window.fetch;
  window.fetch = function(url, init) {
    try {
      if (init && init.headers) {
        if (init.headers instanceof Headers) {
          checkHeader('authorization', init.headers.get('authorization'));
        } else if (typeof init.headers === 'object') {
          for (var k in init.headers) checkHeader(k, init.headers[k]);
        }
      }
    } catch(e) {}
    return origFetch.apply(this, arguments);
  };
})();
"""

/**
 * In-app Tidal sign-in using the desktop web player.
 * Intercepts Bearer tokens when the web app authenticates, verifies them, and calls [onConnected].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TidalLoginScreen(
    onConnected: (token: String, userId: String, countryCode: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var handled by remember { mutableStateOf(false) }

    fun handleToken(token: String, view: WebView) {
        if (handled || token.isBlank()) return
        handled = true
        scope.launch {
            val session = TidalLibrary.verifySession(token)
            if (session != null) {
                withContext(Dispatchers.Main) {
                    view.visibility = View.GONE
                    onConnected(token, session.userId, session.countryCode)
                }
            } else {
                handled = false
            }
        }
    }

    class Bridge(private val viewRef: () -> WebView?) {
        @JavascriptInterface
        fun onToken(token: String) {
            val v = viewRef() ?: return
            handleToken(token, v)
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // Tidal refuses mobile browsers, so desktop Chrome user-agent is used
                settings.userAgentString = DESKTOP_USER_AGENT
                setBackgroundColor(0xFF121212.toInt())
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                addJavascriptInterface(Bridge { webView }, "PlonkerCapture")

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = !isLoginHost(request.url)

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val authHeader = request.requestHeaders["authorization"]
                            ?: request.requestHeaders["Authorization"]
                        if (authHeader?.startsWith("Bearer ", ignoreCase = true) == true) {
                            val token = authHeader.substring(7).trim()
                            handleToken(token, view)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        canGoBack = view.canGoBack()
                        view.evaluateJavascript(HOOK_JS, null)
                    }
                }
                webView = this
                loadUrl(LOGIN_URL)
            }
        },
        onRelease = { it.destroy() },
    )

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }
}
