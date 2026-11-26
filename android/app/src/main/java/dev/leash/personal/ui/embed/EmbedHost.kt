package dev.leash.personal.ui.embed

import dev.leash.personal.core.FileRef

/** Antigravity "generative UI" widgets: an HTML file the agent wrote, named by `<agent-embed src="file:///…">` in its reply. */
object AgentEmbed {
    /** Local path of a `file://` (or absolute) src; the contract only defines local artifact files. */
    fun localPath(src: String): String? {
        val s = src.trim()
        if (s.startsWith("/")) return s
        if (!s.lowercase().startsWith("file://")) return null
        return FileRef.pathFromUri(s)
    }

    fun title(src: String): String = localPath(src)?.trimEnd('/')?.substringAfterLast('/') ?: src
}

/**
 * The page around an embed: agy's host contract (theme variables, html.light / .dark + `themechange`, a 14 px transparent body,
 * a CSP that only allows the gstatic Tailwind script, height from content), ported from ios Views/AgentEmbedView.swift.
 * Pure text, so it is tested on the JVM.
 */
object EmbedHost {
    /** agy's semantic tokens (generative_ui SKILL.md), filled from our Orbit palette (same values as iOS). */
    val themes: Map<String, Map<String, String>> = mapOf(
        "dark" to sortedMapOf(
            "background" to "#12141C", "content" to "#07080D", "card" to "#181B26", "sidebar" to "#0C0D14",
            "card-border" to "rgba(255,255,255,0.10)", "border" to "rgba(255,255,255,0.14)",
            "foreground" to "#F2F3F8", "muted-foreground" to "#9AA0B4", "placeholder" to "#646A7E", "code-foreground" to "#B9BDFF",
            "primary" to "#8088FF", "primary-foreground" to "#0A0B12", "secondary" to "#232634", "secondary-foreground" to "#F2F3F8",
            "accent" to "#4FD8EE", "link" to "#8088FF", "success" to "#3DDC84", "warning" to "#FFB340", "error" to "#FF6B6B",
            "chart-1" to "#8088FF", "chart-2" to "#4FD8EE", "chart-3" to "#3DDC84", "chart-4" to "#FFB340", "chart-5" to "#FF5CA8", "chart-6" to "#B98CFF",
        ),
        "light" to sortedMapOf(
            "background" to "#FFFFFF", "content" to "#F4F5FA", "card" to "#FFFFFF", "sidebar" to "#ECEEF5",
            "card-border" to "rgba(11,16,32,0.10)", "border" to "rgba(11,16,32,0.14)",
            "foreground" to "#0B0D14", "muted-foreground" to "#5A6072", "placeholder" to "#8F95A6", "code-foreground" to "#3D41A8",
            "primary" to "#4B50E6", "primary-foreground" to "#FFFFFF", "secondary" to "#ECEEF5", "secondary-foreground" to "#0B0D14",
            "accent" to "#0A9CB8", "link" to "#4B50E6", "success" to "#1E9E5A", "warning" to "#C96A00", "error" to "#D43A3A",
            "chart-1" to "#4B50E6", "chart-2" to "#0A9CB8", "chart-3" to "#1E9E5A", "chart-4" to "#C96A00", "chart-5" to "#C0267E", "chart-6" to "#7B4FD6",
        ),
    )

    /** Pages that expect WKWebView's `webkit.messageHandlers` reach the host through the one string-argument JS interface. */
    const val INTERFACE_NAME = "agHostAndroid"
    private const val ANDROID_SHIM = "window.webkit={messageHandlers:{agHost:{postMessage:function(m){try{agHostAndroid.post(JSON.stringify(m));}catch(e){}}}}};"

    const val HEAD_TAGS = """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' https://www.gstatic.com/antigravity/; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; form-action 'none'; base-uri 'none'">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover">"""

    /**
     * CSP + viewport + the host script go into the HTML text itself, right after <head> (or the doctype, where the parser opens an
     * implicit head): a CSP <meta> outside <head> is ignored, and running the host script before the page's own keeps its theme
     * class set before every page script runs.
     */
    fun prepare(html: String, dark: Boolean): String {
        val inject = HEAD_TAGS + "\n<script>" + bootstrap(dark) + "</script>\n"
        for (pattern in listOf("""<head\b[^>]*>""", """<!doctype[^>]*>""")) {
            val m = Regex(pattern, RegexOption.IGNORE_CASE).find(html) ?: continue
            return html.substring(0, m.range.last + 1) + "\n" + inject + html.substring(m.range.last + 1)
        }
        return inject + html
    }

    fun themesJson(): String = themes.entries.joinToString(",", "{", "}") { (mode, tokens) ->
        "\"" + mode + "\":" + tokens.entries.joinToString(",", "{", "}") { (k, v) -> "\"" + k + "\":\"" + v + "\"" }
    }

    fun bootstrap(dark: Boolean): String =
        ANDROID_SHIM + SCRIPT.replace("__AG_THEMES__", themesJson()).replace("__AG_MODE__", if (dark) "\"dark\"" else "\"light\"")

    /** Extracts the height the page reports (`{height: 123.5}`), clamped like iOS: 1 ... 4000 px, rounded up. */
    fun heightFrom(json: String): Float? {
        val m = Regex(""""height"\s*:\s*(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)""").find(json) ?: return null
        val h = m.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return Math.ceil(h.coerceIn(1.0, 4000.0)).toFloat()
    }

    private const val SCRIPT = """
(function () {
  var THEMES = __AG_THEMES__;
  var d = document.documentElement;
  function el(tag, attrs) { var e = document.createElement(tag); for (var k in attrs) e.setAttribute(k, attrs[k]); return e; }
  function put(e) { var h = document.head; if (h) h.insertBefore(e, h.firstChild); else d.insertBefore(e, d.firstChild); }
  var base = el('style', { id: 'ag-host-base' });
  base.textContent = 'html{-webkit-text-size-adjust:100%;background:transparent}' +
    'body{margin:0;padding:0;background:transparent;color:var(--foreground);font-size:14px;line-height:1.45;' +
    'font-family:system-ui,-apple-system,BlinkMacSystemFont,"Roboto",sans-serif;-webkit-tap-highlight-color:transparent}';
  var theme = el('style', { id: 'ag-host-theme' });
  function apply(mode) {
    var v = THEMES[mode] || {}, css = ':root{color-scheme:' + mode + ';';
    for (var k in v) css += '--' + k + ':' + v[k] + ';';
    theme.textContent = css + '}';
    d.classList.remove(mode === 'dark' ? 'light' : 'dark');
    d.classList.add(mode);
  }
  apply(__AG_MODE__);
  put(base); put(theme);
  window.__agApplyTheme = function (mode) { apply(mode); window.dispatchEvent(new Event('themechange')); };

  // Height comes from the content (body), never the viewport, so the native frame can follow it.
  var last = -1, queued = false;
  function measure() {
    queued = false;
    var b = document.body; if (!b) return;
    var cs = getComputedStyle(b);
    var h = Math.ceil(Math.max(d.scrollHeight, b.scrollHeight, b.getBoundingClientRect().bottom + (parseFloat(cs.marginBottom) || 0), b.getBoundingClientRect().height + (parseFloat(cs.marginTop) || 0) + (parseFloat(cs.marginBottom) || 0)));
    if (Math.abs(h - last) < 1) return;
    last = h;
    try { window.webkit.messageHandlers.agHost.postMessage({ height: h }); } catch (e) {}
  }
  // rAF batches layout bursts; the timeout covers hosts that throttle rAF (hidden/offscreen web views).
  function report() { if (!queued) { queued = true; requestAnimationFrame(measure); setTimeout(function () { if (queued) measure(); }, 120); } }
  function start() {
    if (document.head && base.parentNode !== document.head) { put(base); put(theme); }
    if (window.ResizeObserver && document.body) new ResizeObserver(report).observe(document.body);
    report();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
  window.addEventListener('load', report);
  window.__agReport = report;
})();
"""
}
