package com.vknxworks.mailctrl.ui.message

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes an HTML message safe to show:
 * - pictures inside the message (cid:) become the data that was downloaded with it;
 * - a Content-Security-Policy allows no scripts, frames, forms or plugins at all, and nothing from the internet
 *   unless [remote] (pictures, styles and fonts only, even then);
 * - a light page style keeps text readable and pictures within the screen.
 */
fun prepareHtml(html: String, inlineImages: Map<String, String>, remote: Boolean): String {
    // A <meta http-equiv="refresh"> would make the page try to go somewhere by itself: it's removed.
    val withInline = CID.replace(META_REFRESH.replace(html, "")) { m ->
        val id = m.groupValues[1].trim()
        val decoded = runCatching { java.net.URLDecoder.decode(id, "UTF-8") }.getOrDefault(id)
        inlineImages[id] ?: inlineImages[decoded] ?: "data:,"
    }
    val net = if (remote) " https: http:" else ""
    val csp = "default-src 'none'; img-src data:$net; style-src 'unsafe-inline'$net; font-src data:$net; " +
        "script-src 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; form-action 'none'; base-uri 'none'; media-src 'none'; connect-src 'none'"
    val head = """
        <!doctype html>
        <meta charset="utf-8">
        <meta http-equiv="Content-Security-Policy" content="$csp">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="referrer" content="no-referrer">
        <style>
          html, body { margin: 0; padding: 0; background: #ffffff; color: #111111; }
          body { padding: 14px 16px; font-family: sans-serif; font-size: 15px; line-height: 1.45; overflow-wrap: anywhere; word-wrap: break-word; }
          img { max-width: 100% !important; height: auto !important; }
          pre { white-space: pre-wrap; }
          blockquote { margin: 0 0 0 8px; padding-left: 10px; border-left: 3px solid #cccccc; color: #444444; }
          a { color: #5b2fc9; }
        </style>
    """.trimIndent()
    return head + "\n" + withInline
}

/** cid: links to pictures sent inside the message. */
private val CID = Regex("""(?i)cid:([^"'\s>)]+)""")

/** <meta http-equiv="refresh" …>, which redirects the page without a tap. */
private val META_REFRESH = Regex("""(?is)<meta[^>]*http-equiv\s*=\s*["']?\s*refresh[^>]*>""")

/**
 * A WebView that can't run scripts, open files, reach other apps' content or (unless allowed) the internet, and
 * never navigates itself: every link tap goes to [onLink], which asks before opening it in the browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlView(html: String, remote: Boolean, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val link = rememberUpdatedState(onLink)
    // What the WebView shows now, so it only loads again when the page or the internet setting changes.
    val loaded = remember { arrayOfNulls<Pair<String, Boolean>>(1) }
    // Read on WebView's own background thread (shouldInterceptRequest), so not the view's tag.
    val allowNetwork = remember { AtomicBoolean(false) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(Color.WHITE)
                isVerticalScrollBarEnabled = false
                settings.apply {
                    javaScriptEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    setGeolocationEnabled(false)
                    setSupportMultipleWindows(false)
                    // No look-ups of links with an online service while you read.
                    safeBrowsingEnabled = false
                    mediaPlaybackRequiresUserGesture = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    loadsImagesAutomatically = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    textZoom = 100
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    // Nothing from the internet until update() says it's allowed.
                    blockNetworkImage = true
                    blockNetworkLoads = true
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return true
                        // Only a real tap asks to open a link; anything the page does by itself is just dropped.
                        if (request.hasGesture()) link.value(url)
                        return true
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val scheme = request?.url?.scheme?.lowercase()
                        // Belt and braces next to blockNetworkLoads: nothing from the internet unless allowed.
                        val allowed = when (scheme) {
                            "data" -> true
                            "http", "https" -> allowNetwork.get()
                            else -> false
                        }
                        return if (allowed) null else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
            }
        },
        update = { view ->
            val key = html to remote
            if (loaded[0] != key) {
                loaded[0] = key
                allowNetwork.set(remote)
                view.settings.blockNetworkImage = !remote
                view.settings.blockNetworkLoads = !remote
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}
