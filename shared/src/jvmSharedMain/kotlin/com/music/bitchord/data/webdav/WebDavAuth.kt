package com.music.bitchord.data.webdav

import java.net.URI
import java.util.Locale

/**
 * In-memory credentials for WebDAV HTTP requests.
 *
 * Held outside SharedPreferences so the network layer never touches prefs:
 * [com.music.bitchord.data.settings.AppSettings] publishes here on init and
 * on every WebDAV edit.
 *
 * Credentials are strictly bound to the configured scheme, host, port, and base path.
 * Requests to other paths on the same host, wrong schemes, or external sources
 * are never authorized.
 */
object WebDavAuth {
    @Volatile var scheme: String? = null
    @Volatile var host: String? = null
    @Volatile var port: Int = -1
    @Volatile var basePath: String? = null
    @Volatile var authHeader: String? = null

    /** Updates credentials with full endpoint isolation. */
    fun update(
        scheme: String?,
        host: String?,
        port: Int,
        basePath: String?,
        header: String?,
    ) {
        this.scheme = scheme?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        this.host = host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        this.port = port
        this.basePath = basePath?.trimEnd('/')?.takeIf { it.isNotBlank() && it != "/" }
        this.authHeader = header?.takeIf { it.isNotBlank() }
    }

    /** Legacy / simplified update matching by host and header alone. */
    fun update(host: String?, header: String?) {
        update(scheme = null, host = host, port = -1, basePath = null, header = header)
    }

    /**
     * Checks if a request URL should be authorized with WebDAV credentials.
     * Enforces scheme, host, port, and path-prefix validation.
     */
    fun shouldAuthorize(requestUrl: String): Boolean {
        val header = authHeader ?: return false
        if (header.isBlank()) return false
        val expectedHost = host ?: return false

        // A bare host without a scheme (e.g. OkHttp request.url.host) matches by host name
        if (!requestUrl.contains("://")) {
            val reqHost = requestUrl.substringBefore('/').substringBefore(':').lowercase(Locale.ROOT)
            return reqHost == expectedHost
        }

        val uri = runCatching { URI(requestUrl) }.getOrNull() ?: return false
        val reqHost = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (reqHost != expectedHost) return false

        val configuredScheme = scheme
        if (configuredScheme != null && uri.scheme != null) {
            val reqScheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            // Disallow downgrade from HTTPS to HTTP
            if (configuredScheme == "https" && reqScheme != "https") return false
            if (configuredScheme != reqScheme && configuredScheme != "http") return false
        }

        if (port != -1 && uri.scheme != null) {
            val reqPort = if (uri.port != -1) uri.port else if (uri.scheme?.equals("https", ignoreCase = true) == true) 443 else 80
            val expPort = if (port != -1) port else if (configuredScheme == "https") 443 else 80
            if (reqPort != expPort) return false
        }

        val prefix = basePath
        if (prefix != null && uri.path != null) {
            val reqPath = (uri.rawPath ?: uri.path).orEmpty().trimEnd('/')
            val normPrefix = prefix.trimEnd('/')
            if (!reqPath.equals(normPrefix, ignoreCase = true) &&
                !reqPath.lowercase(Locale.ROOT).startsWith("$normPrefix/".lowercase(Locale.ROOT))
            ) {
                return false
            }
        }

        return true
    }
}

