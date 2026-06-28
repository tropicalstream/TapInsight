package com.TapLinkX3.app

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Conservative WebView request blocker shared by the TapInsight browser views.
 * Keep first-party app/dashboard/media URLs untouched; block common ad and
 * telemetry hosts plus obvious ad-path requests on third-party pages.
 */
object WebAdBlocker {
    fun intercept(url: String?): WebResourceResponse? {
        if (!shouldBlock(url)) return null
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            mapOf(
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*"
            ),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return false

        val host = uri.host?.lowercase()?.trim('.') ?: return false
        val path = uri.encodedPath?.lowercase().orEmpty()
        val query = uri.encodedQuery?.lowercase().orEmpty()

        if (isAllowedHost(host)) return false
        if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        if (AD_HOST_KEYWORDS.any { host.contains(it) }) return true
        if (AD_PATH_KEYWORDS.any { path.contains(it) || query.contains(it) }) return true
        return false
    }

    private fun isAllowedHost(host: String): Boolean {
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") return true
        if (host == "appassets.androidplatform.net") return true
        if (host == "radio.garden" || host.endsWith(".radio.garden")) return true
        if (host == "youtube.com" || host.endsWith(".youtube.com")) return true
        if (host == "youtu.be" || host.endsWith(".youtu.be")) return true
        if (host == "spotify.com" || host.endsWith(".spotify.com")) return true
        if (host == "google.com" || host.endsWith(".google.com")) return true
        if (host == "googleusercontent.com" || host.endsWith(".googleusercontent.com")) return true
        if (host == "gstatic.com" || host.endsWith(".gstatic.com")) return true
        return false
    }

    private val AD_HOSTS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "google-analytics.com",
        "adservice.google.com",
        "adsystem.com",
        "amazon-adsystem.com",
        "adsrvr.org",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "quantserve.com",
        "moatads.com",
        "branch.io",
        "segment.io",
        "segment.com",
        "hotjar.com",
        "fullstory.com",
        "sentry.io",
        "facebook.net",
        "connect.facebook.net",
        "tiktok.com",
        "analytics.tiktok.com",
        "snapads.com"
    )

    private val AD_HOST_KEYWORDS = setOf(
        "adserver",
        "adservice",
        "adnxs",
        "tracking",
        "telemetry",
        "metrics",
        "beacon"
    )

    private val AD_PATH_KEYWORDS = setOf(
        "/ads",
        "/ad/",
        "/ad?",
        "/advert",
        "/analytics",
        "/tracking",
        "/track?",
        "/beacon",
        "/pixel",
        "google_ads",
        "prebid"
    )
}
