package dev.leash.personal.ui.embed

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.leash.personal.R
import dev.leash.personal.ui.components.LeashFullScreen
import dev.leash.personal.ui.components.LocalAppModel
import dev.leash.personal.ui.components.LocalContentGrew
import dev.leash.personal.ui.components.LocalTranscript
import dev.leash.personal.ui.design.Leash
import dev.leash.personal.ui.design.LeashIcons
import dev.leash.personal.ui.design.LeashShapes
import dev.leash.personal.ui.design.PressScale
import dev.leash.personal.ui.design.Spinner
import dev.leash.personal.ui.design.components.ButtonSize
import dev.leash.personal.ui.design.components.ButtonStyle
import dev.leash.personal.ui.design.components.GlassCircleButton
import dev.leash.personal.ui.design.components.LeashButton
import dev.leash.personal.ui.design.pressable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Loaded embed HTML and the last measured height per agent|src, so scrolling a lazy transcript doesn't refetch or jump. */
object EmbedCache {
    val html = HashMap<String, String>()
    val height = HashMap<String, Float>()
}

private const val INLINE_MAX_DP = 800f

/** Fetches the widget file through bridge/readFile; null text means the bridge says it is not usable (the message says why). */
private class EmbedFile(val text: String?, val error: String?)

private suspend fun loadEmbed(model: dev.leash.personal.core.AppModel, src: String, agent: String, tooBig: String, notHtml: String, unsupported: String): EmbedFile {
    val path = AgentEmbed.localPath(src) ?: return EmbedFile(null, unsupported)
    val (text, truncated, binary) = model.readFile(path, agent)
    if (binary || text == null) return EmbedFile(null, notHtml)
    if (truncated) return EmbedFile(null, tooBig)
    return EmbedFile(text, null)
}

/**
 * An Antigravity widget in a transcript (ios AgentEmbedView): the HTML file is fetched with bridge/readFile and runs in a
 * locked-down WebView that copies agy's host contract. Inline up to 800 dp tall, the expand button opens it full screen.
 */
@Composable
fun AgentEmbedView(src: String, modifier: Modifier = Modifier) {
    val model = LocalAppModel.current
    val transcript = LocalTranscript.current
    val grew = LocalContentGrew.current
    val key = transcript.agent + "|" + src
    var html by remember(key) { mutableStateOf(EmbedCache.html[key]) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var height by remember(key) { mutableStateOf(EmbedCache.height[key] ?: 0f) }   // 0 = not measured yet
    var attempt by remember(key) { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    val tooBig = stringResource(R.string.embed_too_large)
    val notHtml = stringResource(R.string.embed_not_html)
    val unsupported = stringResource(R.string.embed_unsupported)
    // always refetched: agy often rewrites the same file and embeds it again; the cached copy shows meanwhile
    LaunchedEffect(key, attempt) {
        val m = model ?: return@LaunchedEffect
        error = null
        try {
            val r = loadEmbed(m, src, transcript.agent, tooBig, notHtml, unsupported)
            if (r.text == null) { error = r.error; html = null; return@LaunchedEffect }
            EmbedCache.html[key] = r.text
            if (html != r.text) html = r.text
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (html == null) error = e.message ?: e.toString() }
    }
    val c = Leash.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(LeashIcons.embed, null, Modifier.size(14.dp), tint = c.textSecondary)
            Text(AgentEmbed.title(src), Modifier.weight(1f), style = Leash.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            val label = stringResource(R.string.embed_fullscreen)
            Box(Modifier.size(48.dp).pressable(enabled = html != null, scale = PressScale.Compact, onClickLabel = label) { fullscreen = true }, contentAlignment = Alignment.Center) {
                Icon(LeashIcons.fullscreen, label, Modifier.size(18.dp), tint = c.textSecondary)
            }
        }
        val h = html
        when {
            h != null -> EmbedWebView(
                h, scrollable = height > INLINE_MAX_DP,
                modifier = Modifier.fillMaxWidth().height((if (height == 0f) 320f else height.coerceIn(40f, INLINE_MAX_DP)).dp),
                onHeight = { v -> if (v != height) { height = v; EmbedCache.height[key] = v; grew() } },
            )
            error != null -> Row(
                Modifier.fillMaxWidth().clip(LeashShapes.inset).background(c.bgSunken).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(LeashIcons.warning, null, Modifier.size(18.dp), tint = c.attention)
                Text(error!!, Modifier.weight(1f), style = Leash.type.footnote, color = c.textSecondary)
                LeashButton(stringResource(R.string.retry), { attempt++ }, style = ButtonStyle.Plain, size = ButtonSize.Small)
            }
            else -> Box(Modifier.fillMaxWidth().height(maxOf(height, 120f).coerceAtMost(INLINE_MAX_DP).dp), contentAlignment = Alignment.Center) { Spinner(size = 20.dp) }
        }
    }
    if (fullscreen && html != null) EmbedFullscreen(key, src, transcript.agent, html!!) { fullscreen = false }
}

/** The phone counterpart of agy's side-pane artifact view: the widget on the whole page, reloadable. */
@Composable
private fun EmbedFullscreen(key: String, src: String, agent: String, initial: String, onClose: () -> Unit) {
    val model = LocalAppModel.current
    var html by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val tooBig = stringResource(R.string.embed_too_large)
    val notHtml = stringResource(R.string.embed_not_html)
    val unsupported = stringResource(R.string.embed_unsupported)
    // the card may have been showing its cached copy when tapped: catch up, and keep what shows if the fetch fails
    LaunchedEffect(reload) {
        val m = model ?: return@LaunchedEffect
        try {
            val r = loadEmbed(m, src, agent, tooBig, notHtml, unsupported)
            if (r.text == null) { error = r.error; return@LaunchedEffect }
            error = null
            if (html != r.text) { html = r.text; EmbedCache.html[key] = r.text }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {}
    }
    // never leave the widget hidden if the page reports nothing (script blocked, slow CDN)
    LaunchedEffect(Unit) { delay(1_500); shown = true }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "reveal")
    LeashFullScreen(
        title = AgentEmbed.title(src), onDismiss = onClose, edgeVisible = false,
        actions = { GlassCircleButton(LeashIcons.refresh, stringResource(R.string.embed_reload), { reload++ }) },
    ) { top ->
        val c = Leash.colors
        if (error != null) Column(Modifier.fillMaxSize().padding(top = top, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(LeashIcons.warning, null, Modifier.size(28.dp), tint = c.attention)
            Text(error!!, Modifier.padding(top = 8.dp), style = Leash.type.footnote, color = c.textSecondary)
        } else EmbedWebView(
            html, scrollable = true,
            modifier = Modifier.fillMaxSize().padding(top = top, start = 12.dp, end = 12.dp).navigationBarsPadding().alpha(alpha),
            onHeight = { shown = true }, onLoaded = { shown = true },
        )
    }
}

/** What the page can call: its height report (any thread; the JS interface runs off the main one). */
private class HostBridge(private val onHeight: (Float) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun post(json: String) { EmbedHost.heightFrom(json)?.let { h -> main.post { onHeight(h) } } }
}

/**
 * The sandbox: JavaScript on (widgets are scripts), no file or content access, no storage, no window.open, nothing but the
 * widget's own HTML as a document. Links go to the browser; the frame never navigates away.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbedWebView(html: String, scrollable: Boolean, modifier: Modifier, onHeight: (Float) -> Unit = {}, onLoaded: () -> Unit = {}) {
    val dark = Leash.colors.dark
    val context = LocalContext.current
    val heightCb = remember { arrayOf(onHeight) }.also { it[0] = onHeight }
    val loadedCb = remember { arrayOf(onLoaded) }.also { it[0] = onLoaded }
    class State(var loaded: String? = null, var isDark: Boolean = false, var committed: Boolean = false)
    val st = remember { State() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    databaseEnabled = false
                    setGeolocationEnabled(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    useWideViewPort = true
                    loadWithOverviewMode = false
                    setSupportZoom(false)
                    builtInZoomControls = false
                    textZoom = 100
                }
                addJavascriptInterface(HostBridge { h -> heightCb[0](h) }, EmbedHost.INTERFACE_NAME)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url
                        if (u.scheme?.lowercase() in setOf("http", "https")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true   // widgets stay put: nothing navigates the frame away
                    }
                    override fun onPageCommitVisible(view: WebView, url: String?) { st.committed = true }
                    override fun onPageFinished(view: WebView, url: String?) { loadedCb[0]() }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) loadedCb[0]() }
                }
            }
        },
        update = { wv ->
            wv.isVerticalScrollBarEnabled = scrollable
            wv.overScrollMode = if (scrollable) android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS else android.view.View.OVER_SCROLL_NEVER
            if (st.loaded != html) {
                st.loaded = html
                st.isDark = dark
                wv.loadDataWithBaseURL(null, EmbedHost.prepare(html, dark), "text/html", "utf-8", null)
            } else if (st.isDark != dark) {
                st.isDark = dark
                wv.evaluateJavascript("window.__agApplyTheme && window.__agApplyTheme('" + (if (dark) "dark" else "light") + "')", null)
            }
        },
        onRelease = { wv ->
            wv.stopLoading()
            wv.removeJavascriptInterface(EmbedHost.INTERFACE_NAME)
            wv.destroy()
        },
    )
}
