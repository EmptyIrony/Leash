import SwiftUI
import WebKit

// Antigravity "generative UI" widgets: agy writes a self-contained HTML file into the conversation's
// brain dir and puts <agent-embed src="file:///…/widget.html"></agent-embed> in its reply.
// We fetch the file through bridge/readFile and run it in a sandboxed WKWebView that mimics agy's host
// iframe: theme variables, html.light/.dark + 'themechange', 14px transparent body, CSP that only allows
// the gstatic Tailwind script, content-derived height capped at 800pt.

extension EnvironmentValues {
    /// Which bridge agent owns the transcript (embeds are read from that agent's host).
    @Entry var embedAgent: String = "agy"
    /// Set by `embedFullscreenHost()`; nil means no host above, and a card falls back to its own sheet.
    @Entry var embedPresenter: EmbedPresenter? = nil
}

enum AgentEmbed {
    enum Piece: Equatable { case text(String), embed(String) }

    private static let tag = try! NSRegularExpression(
        pattern: #"<agent-embed\b[^>]*?\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)')[^>]*>(?:\s*</agent-embed\s*>)?"#,
        options: [.caseInsensitive])
    private static let stray = try! NSRegularExpression(pattern: #"</?agent-embed\b[^>]*>"#, options: [.caseInsensitive])

    /// Splits one markdown line into text and embeds; nil when the line has nothing embed-related.
    /// A tag still being streamed (no closing ">" yet, or a trailing "<agent-em") is hidden.
    static func pieces(_ line: String) -> [Piece]? {
        guard line.contains("<") else { return nil }
        let ns = line as NSString
        let matches = tag.matches(in: line, range: NSRange(location: 0, length: ns.length))
        var out: [Piece] = []
        var cursor = 0
        func text(_ s: String) {
            let cleaned = stray.stringByReplacingMatches(in: s, range: NSRange(location: 0, length: (s as NSString).length), withTemplate: "")
            if !cleaned.trimmingCharacters(in: .whitespaces).isEmpty { out.append(.text(cleaned)) }
        }
        for m in matches {
            text(ns.substring(with: NSRange(location: cursor, length: m.range.location - cursor)))
            let r = m.range(at: 1).location != NSNotFound ? m.range(at: 1) : m.range(at: 2)
            let src = ns.substring(with: r).trimmingCharacters(in: .whitespaces)
            if !src.isEmpty { out.append(.embed(src)) }
            cursor = m.range.location + m.range.length
        }
        var tail = ns.substring(from: cursor)
        if let open = tail.range(of: "<agent-embed", options: .caseInsensitive) {
            tail = String(tail[..<open.lowerBound])                    // unterminated tag
        } else if let lt = tail.lastIndex(of: "<"), tail.distance(from: lt, to: tail.endIndex) >= 3,
                  "<agent-embed".hasPrefix(tail[lt...].lowercased()) {
            tail = String(tail[..<lt])                                  // "<agent-em" mid-stream
        }
        if matches.isEmpty && tail == line && stray.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)) == nil { return nil }
        text(tail)
        return out
    }

    /// Local path of a `file://` (or absolute) src; agy's contract only defines local artifact files.
    static func localPath(_ src: String) -> String? {
        if src.hasPrefix("/") { return src }
        guard let u = URL(string: src) ?? URL(string: src.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) ?? ""),
              u.scheme?.lowercased() == "file", !u.path.isEmpty else { return nil }
        return u.path
    }

    static func title(_ src: String) -> String {
        localPath(src).map { ($0 as NSString).lastPathComponent } ?? src
    }
}

/// Loaded embed HTML (and last measured height) per path, so scrolling a lazy transcript doesn't refetch.
@Observable
@MainActor
final class EmbedCache {
    static let shared = EmbedCache()
    @ObservationIgnored var html: [String: String] = [:]
    @ObservationIgnored var height: [String: CGFloat] = [:]
    /// Bumped when full screen fetched a newer copy, so the card it zoomed out of (still mounted underneath)
    /// shows the same thing when the cover zooms back into it.
    private(set) var revision: [String: Int] = [:]

    func refreshed(_ text: String, for key: String) {
        html[key] = text
        revision[key, default: 0] += 1
    }
}

enum EmbedContent: Equatable { case html(String) }

// MARK: - Full-screen host

/// One widget opened full screen. `id` is the card key (agent + "|" + src), which is also the zoom source ID.
struct EmbedFullscreenRequest: Identifiable {
    let id: String
    let title: String
    let src: String
    let agent: String
    let content: EmbedContent
}

/// What a card needs from the host: the namespace its zoom source lives in, and how to open full screen.
struct EmbedPresenter: Equatable {
    let namespace: Namespace.ID
    let present: (EmbedFullscreenRequest) -> Void

    // The closure is rebuilt on every host update but always writes the same @State, so only the namespace
    // decides equality (keeps every card from re-rendering when the host does).
    static func == (a: EmbedPresenter, b: EmbedPresenter) -> Bool { a.namespace == b.namespace }
}

extension View {
    /// Presents agent widgets full screen, zooming out of (and back into) their transcript card. Apply once,
    /// above the transcript: the presenting state must outlive the lazily recycled rows.
    func embedFullscreenHost() -> some View { modifier(EmbedFullscreenHost()) }
}

private struct EmbedFullscreenHost: ViewModifier {
    @Namespace private var ns
    @State private var request: EmbedFullscreenRequest?

    func body(content: Content) -> some View {
        content
            .environment(\.embedPresenter, EmbedPresenter(namespace: ns) { request = $0 })
            .fullScreenCover(item: $request) { req in
                // Meant to turn interactive dismiss off: a zoom cover dismisses on a downward drag anywhere (or a pinch)
                // whenever its content is scrolled to the top, and a widget shorter than the screen always is, so
                // dragging a chart, slider or canvas would close it. The close button is the way out (this also avoids
                // iOS 26.0–26.1 losing the zoom source after an interactive dismiss). Whether the zoom's own gestures
                // honour this modifier is not documented; verify on device.
                EmbedFullscreen(request: req)
                    .interactiveDismissDisabled()
                    .navigationTransition(.zoom(sourceID: req.id, in: ns))
            }
    }
}

/// The whole card is the zoom source when a host is present.
private struct EmbedZoomSource: ViewModifier {
    let id: String
    let namespace: Namespace.ID?

    @ViewBuilder func body(content: Content) -> some View {
        if let namespace {
            content.matchedTransitionSource(id: id, in: namespace)
        } else {
            content
        }
    }
}

struct AgentEmbedView: View {
    let src: String
    @Environment(AppModel.self) private var model
    @Environment(\.embedAgent) private var agent
    @Environment(\.embedPresenter) private var presenter
    @State private var content: EmbedContent?
    @State private var error: String?
    @State private var height: CGFloat = 0   // 0 = not measured yet
    @State private var fullscreen = false

    static let inlineMax: CGFloat = 800
    private var key: String { agent + "|" + src }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            header
            Group {
                if let content {
                    EmbedWebView(content: content, scrollable: height > Self.inlineMax) { h in
                        height = h
                        EmbedCache.shared.height[key] = h
                    }
                    .frame(height: height == 0 ? 320 : min(max(height, 40), Self.inlineMax))
                } else if let error {
                    HStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle").foregroundStyle(Theme.attention)
                        Text(error).font(.footnote).foregroundStyle(Theme.textSecondary)
                        Spacer()
                        Button("Retry") { Task { await load(force: true) } }.font(.footnote)
                    }
                    .padding(12)
                    .background(Theme.bgSunken, in: RoundedRectangle(cornerRadius: Theme.Radius.inset, style: .continuous))
                } else {
                    ActivitySpinner(size: 20).frame(maxWidth: .infinity).frame(height: min(max(height, 120), Self.inlineMax))
                }
            }
        }
        .textSelection(.disabled)
        .modifier(EmbedZoomSource(id: key, namespace: presenter?.namespace))
        .task(id: key) { await load(force: false) }
        .onChange(of: EmbedCache.shared.revision[key]) {
            if let text = EmbedCache.shared.html[key], content != .html(text) { content = .html(text) }
        }
        // Fallback when no embedFullscreenHost() is above this card.
        .sheet(isPresented: $fullscreen) {
            if let request { EmbedFullscreen(request: request) }
        }
    }

    private var request: EmbedFullscreenRequest? {
        content.map { EmbedFullscreenRequest(id: key, title: AgentEmbed.title(src), src: src, agent: agent, content: $0) }
    }

    private func openFullscreen() {
        if let presenter, let request { presenter.present(request) } else { fullscreen = true }
    }

    private var header: some View {
        HStack(spacing: 6) {
            Image(systemName: "cube.transparent").font(.caption)
            Text(AgentEmbed.title(src)).font(.caption).lineLimit(1).truncationMode(.middle)
            Spacer()
            Button { openFullscreen() } label: {
                Image(systemName: "arrow.up.left.and.arrow.down.right").font(.caption)
                    .padding(.vertical, 4).padding(.leading, 12)
            }
            .disabled(content == nil)
            .accessibilityLabel(Text("Full screen"))
        }
        .foregroundStyle(Theme.textTertiary)
    }

    /// Shows the cached copy at once, then always refetches: agy often rewrites the same file and embeds it again.
    private func load(force: Bool) async {
        if height == 0, let h = EmbedCache.shared.height[key] { height = h }
        guard let path = AgentEmbed.localPath(src) else { error = String(localized: "Unsupported embed source"); return }
        if !force, content == nil, let cached = EmbedCache.shared.html[key] { content = .html(cached) }
        error = nil
        do {
            let r = try await model.readFile(path, agent: agent)
            if r.binary || r.text == nil { error = String(localized: "This embed isn't an HTML file"); content = nil; return }
            if r.truncated { error = String(localized: "This embed is larger than 512 KB"); content = nil; return }
            let text = r.text!
            EmbedCache.shared.html[key] = text
            if content != .html(text) { content = .html(text) }
        } catch {
            if content == nil { self.error = error.localizedDescription }
        }
    }
}

/// Phone counterpart of agy's side-pane artifact view.
struct EmbedFullscreen: View {
    let request: EmbedFullscreenRequest
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var content: EmbedContent
    @State private var error: String?
    /// The web view paints nothing until its first layout; until then the bare background stands in for it.
    @State private var shown = false

    init(request: EmbedFullscreenRequest) {
        self.request = request
        _content = State(initialValue: request.content)
    }

    var body: some View {
        NavigationStack {
            page
                .background { SpaceBackground(stars: false) }
                .navigationTitle(request.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(role: .close) { dismiss() } }
                    ToolbarItem(placement: .primaryAction) {
                        Button { Task { await reload() } } label: { Image(systemName: "arrow.clockwise") }.accessibilityLabel(Text("Reload"))
                    }
                }
        }
        // The card may have been showing its cached copy when tapped; catch up like the old sheet did.
        .task { await reload() }
    }

    @ViewBuilder private var page: some View {
        if let error {
            VStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle").font(.title2).foregroundStyle(Theme.attention)
                Text(error).font(.footnote).foregroundStyle(Theme.textSecondary).multilineTextAlignment(.center)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            EmbedWebView(content: content, scrollable: true, onHeight: { _ in reveal() }, onLoaded: { reveal() })
                .padding(.horizontal, 12)
                .opacity(shown ? 1 : 0)
                .animation(Motion.quick, value: shown)
                .task {
                    // Never leave the widget hidden if the page reports nothing (script blocked, slow CDN).
                    try? await Task.sleep(for: .seconds(1.5))
                    reveal()
                }
        }
    }

    private func reveal() {
        if !shown { shown = true }
    }

    /// Re-reads the file itself (the card that opened this may have been recycled); like the card's load,
    /// a failed fetch keeps what is showing.
    private func reload() async {
        guard let path = AgentEmbed.localPath(request.src) else { return }
        do {
            let r = try await model.readFile(path, agent: request.agent)
            if r.binary || r.text == nil { error = String(localized: "This embed isn't an HTML file"); return }
            if r.truncated { error = String(localized: "This embed is larger than 512 KB"); return }
            let text = r.text!
            error = nil
            guard content != .html(text) else { return }
            EmbedCache.shared.refreshed(text, for: request.id)
            content = .html(text)
        } catch {}
    }
}

struct EmbedWebView: UIViewRepresentable {
    let content: EmbedContent
    var scrollable: Bool
    var onHeight: (CGFloat) -> Void = { _ in }
    /// The page finished (or failed) loading.
    var onLoaded: () -> Void = {}
    @Environment(\.colorScheme) private var scheme

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let c = context.coordinator
        c.onHeight = onHeight
        c.onLoaded = onLoaded
        c.dark = scheme == .dark
        let ucc = WKUserContentController()
        ucc.add(WeakMessageHandler(c), name: "agHost")
        let cfg = WKWebViewConfiguration()
        cfg.userContentController = ucc
        cfg.websiteDataStore = .nonPersistent()
        cfg.allowsInlineMediaPlayback = true
        cfg.dataDetectorTypes = []
        let wv = WKWebView(frame: .zero, configuration: cfg)
        wv.isOpaque = false
        wv.backgroundColor = .clear
        wv.scrollView.backgroundColor = .clear
        wv.scrollView.contentInsetAdjustmentBehavior = .never
        wv.navigationDelegate = c
        wv.uiDelegate = c
        apply(wv)
        load(wv, c)
        return wv
    }

    func updateUIView(_ wv: WKWebView, context: Context) {
        let c = context.coordinator
        c.onHeight = onHeight
        c.onLoaded = onLoaded
        apply(wv)
        if c.loaded != content { load(wv, c) }
        let dark = scheme == .dark
        if c.dark != dark {
            c.dark = dark
            wv.evaluateJavaScript("window.__agApplyTheme && window.__agApplyTheme('\(dark ? "dark" : "light")')")
        }
    }

    private func apply(_ wv: WKWebView) {
        wv.scrollView.isScrollEnabled = scrollable
        wv.scrollView.bounces = scrollable
    }

    private func load(_ wv: WKWebView, _ c: Coordinator) {
        c.loaded = content
        c.committed = false
        switch content {
        case .html(let s): wv.loadHTMLString(EmbedHost.prepare(s, dark: c.dark), baseURL: nil)
        }
    }

    static func dismantleUIView(_ wv: WKWebView, coordinator: Coordinator) {
        wv.stopLoading()
        wv.configuration.userContentController.removeScriptMessageHandler(forName: "agHost")
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        var onHeight: (CGFloat) -> Void = { _ in }
        var onLoaded: () -> Void = {}
        var dark = false
        var loaded: EmbedContent?
        var committed = false

        func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any], let h = body["height"] as? Double, h.isFinite else { return }
            let clamped = CGFloat(min(max(h, 1), 4000)).rounded(.up)
            DispatchQueue.main.async { self.onHeight(clamped) }
        }

        // Widgets stay put: links open in Safari, nothing navigates the frame away.
        func webView(_ wv: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if action.navigationType == .linkActivated || action.targetFrame == nil {
                if let u = action.request.url, ["http", "https"].contains(u.scheme?.lowercased() ?? "") { UIApplication.shared.open(u) }
                decisionHandler(.cancel)
                return
            }
            // Our own load (and its redirects) may proceed; script-driven main-frame navigations after it may not.
            decisionHandler(action.targetFrame?.isMainFrame == true && committed ? .cancel : .allow)
        }

        func webView(_ wv: WKWebView, didCommit navigation: WKNavigation!) { committed = true }
        func webView(_ wv: WKWebView, didFinish navigation: WKNavigation!) { onLoaded() }
        func webView(_ wv: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { onLoaded() }
        func webView(_ wv: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { onLoaded() }

        func webView(_ wv: WKWebView, createWebViewWith cfg: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let u = action.request.url, ["http", "https"].contains(u.scheme?.lowercased() ?? "") { UIApplication.shared.open(u) }
            return nil
        }
    }
}

/// WKUserContentController retains its handlers; this breaks the cycle with the coordinator.
@MainActor
private final class WeakMessageHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ t: WKScriptMessageHandler) { target = t }
    func userContentController(_ ucc: WKUserContentController, didReceive m: WKScriptMessage) { target?.userContentController(ucc, didReceive: m) }
}

enum EmbedHost {
    /// agy's semantic tokens (generative_ui SKILL.md), filled from our Orbit palette.
    static let themes: [String: [String: String]] = [
        "dark": [
            "background": "#12141C", "content": "#07080D", "card": "#181B26", "sidebar": "#0C0D14",
            "card-border": "rgba(255,255,255,0.10)", "border": "rgba(255,255,255,0.14)",
            "foreground": "#F2F3F8", "muted-foreground": "#9AA0B4", "placeholder": "#646A7E", "code-foreground": "#B9BDFF",
            "primary": "#8088FF", "primary-foreground": "#0A0B12", "secondary": "#232634", "secondary-foreground": "#F2F3F8",
            "accent": "#4FD8EE", "link": "#8088FF", "success": "#3DDC84", "warning": "#FFB340", "error": "#FF6B6B",
            "chart-1": "#8088FF", "chart-2": "#4FD8EE", "chart-3": "#3DDC84", "chart-4": "#FFB340", "chart-5": "#FF5CA8", "chart-6": "#B98CFF",
        ],
        "light": [
            "background": "#FFFFFF", "content": "#F4F5FA", "card": "#FFFFFF", "sidebar": "#ECEEF5",
            "card-border": "rgba(11,16,32,0.10)", "border": "rgba(11,16,32,0.14)",
            "foreground": "#0B0D14", "muted-foreground": "#5A6072", "placeholder": "#8F95A6", "code-foreground": "#3D41A8",
            "primary": "#4B50E6", "primary-foreground": "#FFFFFF", "secondary": "#ECEEF5", "secondary-foreground": "#0B0D14",
            "accent": "#0A9CB8", "link": "#4B50E6", "success": "#1E9E5A", "warning": "#C96A00", "error": "#D43A3A",
            "chart-1": "#4B50E6", "chart-2": "#0A9CB8", "chart-3": "#1E9E5A", "chart-4": "#C96A00", "chart-5": "#C0267E", "chart-6": "#7B4FD6",
        ],
    ]

    /// CSP + viewport + the host script go into the HTML text itself, right after <head> (or the doctype, where the
    /// parser opens an implicit head): a CSP <meta> outside <head> is ignored, and running the host script after <html>
    /// is parsed keeps the page's own <html class> and still beats every page script (theme class is set before they run).
    static let headTags = """
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' https://www.gstatic.com/antigravity/; \
style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; form-action 'none'; base-uri 'none'">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover">
"""

    static func prepare(_ html: String, dark: Bool) -> String {
        let inject = headTags + "\n<script>" + bootstrap(dark: dark) + "</script>\n"
        for pattern in [#"<head\b[^>]*>"#, #"<!doctype[^>]*>"#] {
            if let r = html.range(of: pattern, options: [.regularExpression, .caseInsensitive]) {
                return html.replacingCharacters(in: r, with: String(html[r]) + "\n" + inject)
            }
        }
        return inject + html
    }

    static func bootstrap(dark: Bool) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: themes, options: [.sortedKeys])) ?? Data("{}".utf8)
        return script
            .replacingOccurrences(of: "__AG_THEMES__", with: String(decoding: data, as: UTF8.self))
            .replacingOccurrences(of: "__AG_MODE__", with: dark ? "\"dark\"" : "\"light\"")
    }

    // tools/embed-preview.mjs extracts headTags/themes/script from this file to test the wrapper in Chromium.
    static let script = #"""
(function () {
  var THEMES = __AG_THEMES__;
  var d = document.documentElement;
  function el(tag, attrs) { var e = document.createElement(tag); for (var k in attrs) e.setAttribute(k, attrs[k]); return e; }
  function put(e) { var h = document.head; if (h) h.insertBefore(e, h.firstChild); else d.insertBefore(e, d.firstChild); }
  var base = el('style', { id: 'ag-host-base' });
  base.textContent = 'html{-webkit-text-size-adjust:100%;background:transparent}' +
    'body{margin:0;padding:0;background:transparent;color:var(--foreground);font-size:14px;line-height:1.45;' +
    'font-family:-apple-system,BlinkMacSystemFont,"SF Pro Text","PingFang SC",system-ui,sans-serif;-webkit-tap-highlight-color:transparent}';
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
    var h = Math.ceil(Math.max(b.scrollHeight, b.getBoundingClientRect().height) + (parseFloat(cs.marginTop) || 0) + (parseFloat(cs.marginBottom) || 0));
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
"""#
}
