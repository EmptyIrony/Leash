import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct ChatView: View {
    @Environment(AppModel.self) private var model
    let store: ChatStore
    var openSession: (String) -> Void = { _ in }

    @State private var draft = ""
    @State private var images: [Data] = []
    /// files picked in the composer; stored on the agent's host when the message is sent
    @State private var files: [PendingFile] = []
    @State private var showFileImporter = false
    @State private var uploading = false
    @State private var pickerItems: [PhotosPickerItem] = []
    /// Following the newest output; cleared when the user scrolls up, set again at the bottom or on send.
    @State private var pinned = true
    @State private var position = ScrollPosition(edge: .bottom)
    @State private var userScrolling = false
    @State private var lastAtEnd = true
    @State private var showPhotoPicker = false
    @State private var fileToOpen: String?
    @State private var confirmTurbo = false
    @State private var sendTick = 0
    @State private var stopTick = 0
    @State private var showQuota = false
    @State private var showModelPicker = false
    @State private var showEffort = false
    @AppStorage("turboConfirmed") private var turboConfirmed = false
    @FocusState private var composerFocused: Bool
    @Namespace private var glassNS
    @Namespace private var quotaNS
    /// The optimistic bubble has waited ≥ 2 s for its echo: show "Sending…" under it.
    @State private var sendingSlow = false

    private var pendingHere: [PendingRequest] { model.pendingFor(store.sessionId) }
    private var permissionRequests: [PendingRequest] { pendingHere.filter { $0.isPermission } }
    private var questionRequests: [PendingRequest] { pendingHere.filter { !$0.isPermission } }

    // Split into small pieces so the type checker stays fast.
    var body: some View {
        lifecycle(chrome(scrollArea))
    }

    /// What pinning needs from the scroll view: content height, bottom inset (composer + trays) and whether the
    /// visible area reaches the end.
    private struct ScrollMetrics: Equatable {
        var contentHeight: CGFloat
        var bottomInset: CGFloat
        var atEnd: Bool
    }

    // Pinning is geometry-based (no sentinel row): short conversations sit at the top, long ones open at the
    // bottom, and growth (streaming, a tray appearing, the keyboard) keeps the end in view only while pinned.
    private var scrollArea: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                transcript
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 4)
        }
        .scrollPosition($position)
        .environment(\.embedAgent, store.agent)
        .environment(\.transcriptSession, store.sessionId)
        .defaultScrollAnchor(.bottom, for: .initialOffset)
        .defaultScrollAnchor(.top, for: .alignment)
        .scrollDismissesKeyboard(.interactively)
        .scrollEdgeEffectStyle(.soft, for: [.top, .bottom])
        .onScrollPhaseChange { _, phase in
            userScrolling = phase == .tracking || phase == .interacting || phase == .decelerating
            // a system scroll (status-bar tap to top) also ends here: follow again only if it ended at the bottom
            if phase == .idle { pinned = lastAtEnd }
        }
        .onScrollGeometryChange(for: ScrollMetrics.self) { g in
            let visibleEnd = g.contentOffset.y + g.containerSize.height - g.contentInsets.bottom
            return ScrollMetrics(contentHeight: g.contentSize.height, bottomInset: g.contentInsets.bottom,
                                 atEnd: visibleEnd >= g.contentSize.height - 32)
        } action: { old, new in
            lastAtEnd = new.atEnd
            if userScrolling {
                if pinned != new.atEnd { pinned = new.atEnd }
            } else if pinned && (new.contentHeight != old.contentHeight || new.bottomInset != old.bottomInset) && !new.atEnd {
                position.scrollTo(edge: .bottom)
            } else if new.atEnd && !pinned {
                pinned = true
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { bottomControls }
    }

    private func jumpToBottom() {
        pinned = true
        withAnimation(Motion.layout) { position.scrollTo(edge: .bottom) }
    }

    private var subtitle: String {
        let path = store.cwd.map { model.shortPath($0, agent: store.agent) } ?? ""
        return model.agents.count > 1 ? model.agentLabel(store.agent) + " · " + path : path
    }
    private var imageInput: Bool { model.agent(store.agent)?.imageInput ?? model.imageInput }

    private var fileSheet: Binding<IdentifiedPath?> {
        Binding(get: { fileToOpen.map { IdentifiedPath(path: $0) } }, set: { fileToOpen = $0?.path })
    }

    private func chrome<V: View>(_ content: V) -> some View {
        let base = content
            .background { SpaceBackground(stars: false) }
            .safeAreaInset(edge: .top, spacing: 0) { otherSessionsBanner }
            .navigationTitle(store.title ?? String(localized: "Conversation"))
            .navigationSubtitle(subtitle)
            .navigationBarTitleDisplayMode(.inline)
        return base
            .toolbar { ToolbarItem(placement: .topBarTrailing) { moreMenu } }
            .sheet(item: fileSheet) { f in FileViewer(path: f.path, agent: store.agent, sessionId: store.sessionId) }
            .sheet(isPresented: $showQuota) {
                QuotaSheet()
                    .presentationDetents([.medium, .large])
                    .navigationTransition(.zoom(sourceID: "quota", in: quotaNS))
            }
            .embedFullscreenHost()
            .confirmationDialog("Turn on Turbo?", isPresented: $confirmTurbo, titleVisibility: .visible) {
                Button("Turbo — approve everything", role: .destructive) { enableTurbo() }
            } message: {
                Text("Every command and edit the agent asks for in this conversation will be approved automatically on your Mac.")
            }
    }

    private func enableTurbo() {
        turboConfirmed = true
        model.choose(mode: "turbo", for: store)
    }

    private func lifecycle<V: View>(_ content: V) -> some View {
        let base = content
            .task { await model.attach(store) }
            .task { await model.refreshQuota() }
            .task(id: store.pendingEcho?.id) { await watchSending() }
            .onDisappear { model.detach(store) }
            .onChange(of: pickerItems) { _, items in loadPicked(items) }
            .onChange(of: store.unsentDraft) { _, t in
                // a queued message taken back goes in front of whatever is being typed
                if let t, !t.isEmpty { draft = draft.isEmpty ? t : t + "\n" + draft; store.unsentDraft = nil }
            }
            .onChange(of: store.unsentImages) { _, imgs in
                if let imgs, !imgs.isEmpty { images.insert(contentsOf: imgs, at: 0); store.unsentImages = nil }
            }
        return base
            .haptic(.impact(weight: .light), trigger: sendTick)                                   // message leaves
            .haptic(.impact(flexibility: .rigid, intensity: 0.9), trigger: stopTick)              // hard stop
            .haptic(.warning, trigger: pendingHere.count) { old, new in new > old }               // agent needs you
            .haptic(.success, trigger: store.running) { was, now in was && !now && turnWasLong }  // long task done
            .haptic(.selection, trigger: images.count + files.count) { old, new in new > old }     // photo / file attached
            .onAppear { model.visibleSessionId = store.sessionId }
            .onDisappear { if model.visibleSessionId == store.sessionId { model.visibleSessionId = nil } }
    }

    /// Only celebrate turns that took a while (≥ 8 s) and ended normally.
    private var turnWasLong: Bool {
        guard store.lastStopReason == nil || store.lastStopReason == "end_turn" else { return false }
        guard let start = store.turnStartedAt else { return false }
        return Date().timeIntervalSince(start) >= 8
    }

    private func loadPicked(_ items: [PhotosPickerItem]) {
        guard !items.isEmpty else { return }
        Task {
            for item in items {
                if let data = try? await item.loadTransferable(type: Data.self), let jpeg = ImageTools.jpeg(data) { images.append(jpeg) }
            }
            pickerItems = []
        }
    }

    // MARK: transcript
    @ViewBuilder private var transcript: some View {
        if store.loading {
            HStack(spacing: 10) { Spacer(); ActivitySpinner(size: 18); Text("Loading conversation…").foregroundStyle(Theme.textSecondary); Spacer() }
                .padding(.top, 60)
        } else if store.attached && store.entries.isEmpty && !store.running {
            VStack(spacing: 10) {
                Image(systemName: "sparkles").font(.title2).foregroundStyle(Theme.accent)
                Group { if store.agent == "claude" { Text("Ask Claude anything") } else { Text("Ask Antigravity anything") } }
                    .font(.headline).fontWidth(.expanded)
                if let cwd = store.cwd { Text(verbatim: model.shortPath(cwd, agent: store.agent)).font(Theme.mono).foregroundStyle(Theme.textTertiary) }
            }
            .frame(maxWidth: .infinity).padding(.top, 80)
        }
        let items = store.displayItems()
        ForEach(Array(items.enumerated()), id: \.element.id) { idx, item in
            displayRow(item, isFirst: idx == 0)
        }
        if let stop = store.lastStopReason, stop != "end_turn", !store.running {
            Label("Stopped · \(stop)", systemImage: "stop.circle").font(.caption).foregroundStyle(Theme.textTertiary)
        }
        if let e = store.error {
            Label(e, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(Theme.danger)
        }
        if let by = store.lockedBy {
            VStack(alignment: .leading, spacing: 8) {
                Label(Self.lockText(by), systemImage: "lock.fill").font(.callout).foregroundStyle(Theme.textPrimary)
                Button("Retry") { Task { await model.attach(store) } }.font(.callout.weight(.semibold))
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.bgRaised, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }

    /// Under the optimistic bubble (ChatStore.pendingEcho, drawn as the last user row) once it is taking a while
    /// or the Mac is unreachable.
    private var sendingCaption: some View {
        Text("Sending…")
            .font(.caption)
            .foregroundStyle(Theme.textTertiary)
            .transition(.opacity.animation(Motion.quick))
    }

    private func watchSending() async {
        sendingSlow = false
        guard let p = store.pendingEcho else { return }
        let wait = 2 - Date().timeIntervalSince(p.sentAt)
        if wait > 0 { try? await Task.sleep(for: .seconds(wait)) }
        if !Task.isCancelled { withAnimation(Motion.quick) { sendingSlow = true } }
    }

    @ViewBuilder private func displayRow(_ item: DisplayItem, isFirst: Bool) -> some View {
        switch item {
        case .user(let m):
            // tag-only messages (a slash-command caveat, …) render nothing; "!" output stays next to its command
            if UserBubble.hasContent(m) {
                VStack(alignment: .trailing, spacing: 4) {
                    UserBubble(item: m)
                    if sendingSlow || !model.isConnected, store.pendingEcho?.item === m { sendingCaption }
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.top, isFirst || UserSegment.isContinuation(m.text) ? 0 : 12)
                .transition(Motion.sent)
            }
        case .activity(let g):
            ActivityRow(group: g, store: store,
                        blocked: g.live && !permissionRequests.isEmpty,
                        turnStartedAt: store.turnStartedAt,
                        openFile: { fileToOpen = $0 })
        case .answer(let m):
            // the answer streaming at the end of a running turn reveals its new text as it arrives
            let streaming: Bool = {
                guard store.running, !store.loading, case .message(let last)? = store.entries.last else { return false }
                return last === m
            }()
            AnswerView(item: m, live: streaming)
        case .stats(let s):
            StatsLine(stats: s)
        case .notice(let n):
            NoticeView(item: n)
        case .planDoc(let p):
            PlanCard(item: p)
        }
    }

    // MARK: bottom controls (approval / question tray + composer + selector chips), one glass container
    private var trayVisible: Bool { !permissionRequests.isEmpty || !questionRequests.isEmpty }

    @ViewBuilder private var bottomControls: some View {
        GlassEffectContainer(spacing: 8) {
            VStack(alignment: .leading, spacing: 8) {
                if let req = permissionRequests.first {
                    ApprovalTray(request: req, index: 1, total: permissionRequests.count, openFile: { fileToOpen = $0 })
                        .glassEffectID("tray", in: glassNS)
                        .glassEffectTransition(.materialize)
                        .transition(Motion.tray)
                } else if let req = questionRequests.first {
                    QuestionTray(request: req, index: 1, total: questionRequests.count)
                        .glassEffectID("tray", in: glassNS)
                        .glassEffectTransition(.materialize)
                        .transition(Motion.tray)
                }
                if !pinned {
                    HStack {
                        Spacer()
                        Button(action: jumpToBottom) {
                            Image(systemName: "arrow.down").font(.callout.weight(.semibold)).frame(width: 36, height: 36)
                        }
                        .buttonStyle(.plain)
                        .glassEffect(.regular.interactive(), in: .circle)
                        .glassEffectID("scroll", in: glassNS)
                        .glassEffectTransition(.materialize)
                        .accessibilityLabel("Scroll to bottom")
                    }
                    .transition(Motion.pop)
                }
                if !store.queued.isEmpty {
                    QueueTray(items: store.queued, running: store.running, collapsed: trayVisible, enabled: composerEnabled,
                              edit: { model.unqueue(store, $0, edit: true); composerFocused = true },
                              delete: { model.unqueue(store, $0, edit: false); Haptics.selection() },
                              sendNow: sendQueuedNow)
                        .glassEffectID("queue", in: glassNS)
                        .glassEffectTransition(.materialize)
                        .transition(Motion.tray)
                }
                slab
                if !trayVisible { chipRow.transition(Motion.tray) }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 6)
            .padding(.top, 10)
            .background(alignment: .bottom) { composerFade }
            .animation(Motion.layout, value: permissionRequests.first?.key)
            .animation(Motion.layout, value: questionRequests.first?.key)
            .animation(Motion.layout, value: pinned)
            .animation(Motion.layout, value: store.queued)
        }
    }

    /// Transcript fades out under the composer so glass never sits on top of readable text.
    private var composerFade: some View {
        LinearGradient(stops: [
            .init(color: Theme.bgBase.opacity(0), location: 0),
            .init(color: Theme.bgBase.opacity(0.85), location: 0.35),
            .init(color: Theme.bgBase, location: 1),
        ], startPoint: .top, endPoint: .bottom)
        .ignoresSafeArea(edges: .bottom)
        .allowsHitTesting(false)
    }

    static func lockText(_ by: String) -> String {
        switch by {
        case "cli": return String(localized: "Claude Code in a terminal on your computer is running a task in this conversation. It reopens here when that finishes.")
        case "claude-desktop": return String(localized: "The Claude desktop app is running a task in this conversation. It reopens here when that finishes.")
        case "antigravity": return String(localized: "The Antigravity desktop app is running a task in this conversation. It reopens here when that finishes.")
        default: return String(localized: "Another device is running a task in this conversation. It reopens here when that finishes.")
        }
    }

    /// "!" at the start of the draft switches the composer to shell mode (like Claude Code's CLI).
    private var isShell: Bool { draft.hasPrefix("!") }
    private var shellCommand: String { String(draft.dropFirst()).trimmingCharacters(in: .whitespacesAndNewlines) }

    private var canSend: Bool {
        guard model.isReady(store.agent) && store.attached else { return false }
        if isShell { return !shellCommand.isEmpty && !store.running && !store.shellRunning }
        if uploading { return false }
        return !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !images.isEmpty || !files.isEmpty
    }
    private var composerEnabled: Bool { model.isReady(store.agent) && store.attached }

    private var slab: some View {
        VStack(alignment: .leading, spacing: 8) {
            if isShell { shellStatus.transition(Motion.reveal) }
            if (!images.isEmpty || !files.isEmpty) && !isShell {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(images.enumerated()), id: \.offset) { i, d in
                            if let ui = UIImage(data: d) {
                                Image(uiImage: ui).resizable().scaledToFill().frame(width: 56, height: 56)
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    .overlay(alignment: .topTrailing) {
                                        Button { images.remove(at: i) } label: {
                                            Image(systemName: "xmark.circle.fill").foregroundStyle(Color.white, Color.black.opacity(0.6))
                                        }
                                        .offset(x: 4, y: -4)
                                        .accessibilityLabel("Remove photo")
                                    }
                            }
                        }
                        ForEach(files) { f in
                            ComposerFileChip(file: f, uploading: uploading) { files.removeAll { $0.id == f.id } }
                        }
                    }
                    .padding(.top, 4).padding(.horizontal, 2)
                }
            }
            HStack(alignment: .bottom, spacing: 8) {
                leadingButton
                TextField(placeholder, text: $draft, axis: .vertical)
                    .lineLimit(1...8)
                    .font(isShell ? .body.monospaced() : .body)
                    .autocorrectionDisabled(isShell)
                    .textInputAutocapitalization(isShell ? .never : .sentences)
                    .keyboardType(isShell ? .asciiCapable : .default)
                    .focused($composerFocused)
                    .padding(.vertical, 7)
                    .disabled(!composerEnabled)
                    .onKeyPress(.return, phases: .down) { press in
                        if press.modifiers.contains(.command) { send(); return .handled }
                        return .ignored
                    }
                actionButton
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 6)
        .frame(minHeight: 48)
        .glassEffect(.regular.tint(isShell ? Theme.shell.opacity(0.16) : Theme.bgBase.opacity(0.55)), in: .rect(cornerRadius: 26))
        .glassEffectID("slab", in: glassNS)
        .opacity(composerEnabled ? 1 : 0.5)
        .animation(Motion.quick, value: isShell)
        .photosPicker(isPresented: $showPhotoPicker, selection: $pickerItems, maxSelectionCount: 4, matching: .images)
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            let r = PendingFile.load(result)
            files += r.files
            if !r.tooLarge.isEmpty { store.error = String(localized: "Files over 25 MB can't be attached: \(r.tooLarge.joined(separator: ", "))"); Haptics.error() }
            else if !r.unreadable.isEmpty { store.error = String(localized: "Couldn't read: \(r.unreadable.joined(separator: ", "))"); Haptics.error() }
        }
    }

    /// "+" menu (photos, shell); in shell mode a terminal button that leaves shell mode.
    @ViewBuilder private var leadingButton: some View {
        if isShell {
            Button { draft.removeFirst(); Haptics.selection() } label: {
                Image(systemName: "terminal.fill").font(.body.weight(.semibold)).foregroundStyle(Theme.shell).frame(width: 34, height: 34)
                    .contentTransition(.symbolEffect(.replace))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Leave shell mode")
        } else {
            Menu {
                if imageInput {
                    Button { showPhotoPicker = true } label: { Label("Photo Library", systemImage: "photo.on.rectangle") }
                }
                Button { showFileImporter = true } label: { Label("Choose Files", systemImage: "folder") }
                Divider()
                Button { draft = "!" + draft; composerFocused = true } label: { Label("Run shell command", systemImage: "terminal") }
            } label: {
                Image(systemName: "plus").font(.body.weight(.semibold)).foregroundStyle(Theme.textSecondary).frame(width: 34, height: 34)
                    .contentTransition(.symbolEffect(.replace))
            }
            .menuOrder(.fixed)
            .disabled(!composerEnabled)
            .accessibilityLabel("Add")
        }
    }

    /// Where the command runs, or why it can't run yet.
    @ViewBuilder private var shellStatus: some View {
        HStack(spacing: 6) {
            if store.shellRunning {
                ActivitySpinner(size: 12, color: Theme.shell)
                Text("Running…")
            } else if store.running {
                Image(systemName: "hourglass")
                Text("Shell commands run when the agent finishes")
            } else {
                Image(systemName: "terminal")
                Text("Shell · \(store.cwd.map { model.shortPath($0, agent: store.agent) } ?? "~")")
            }
        }
        .font(.caption.weight(.medium))
        .foregroundStyle(store.running ? Theme.textSecondary : Theme.shell)
        .lineLimit(1).truncationMode(.middle)
        .padding(.horizontal, 8).padding(.top, 4)
    }

    private var placeholder: String {
        if !model.isReady(store.agent) { return model.isConnected ? String(localized: "\(model.agentLabel(store.agent)) is not running") : String(localized: "Mac offline…") }
        if store.running { return model.steers(store.agent) ? String(localized: "Steer the agent…") : String(localized: "Queue a message…") }
        return store.agent == "claude" ? String(localized: "Ask Claude…") : String(localized: "Ask Antigravity…")
    }

    private var hasDraft: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !images.isEmpty || !files.isEmpty
    }

    /// What the primary circle shows. One control whose fill and symbol morph in place between these.
    private enum ActionGlyph: Equatable {
        case stop
        case send(shell: Bool)
        case disabled(shell: Bool)

        var symbol: String {
            switch self {
            case .stop: return "stop.fill"
            case .send(let shell), .disabled(let shell): return shell ? "return" : "arrow.up"
            }
        }
        var isDisabled: Bool {
            if case .disabled = self { return true }
            return false
        }
    }

    private var actionGlyph: ActionGlyph {
        if store.running && !hasDraft { return .stop }
        return canSend ? .send(shell: isShell) : .disabled(shell: isShell)
    }

    private var actionButton: some View {
        HStack(spacing: 6) {
            if store.running && hasDraft {
                secondaryStop.transition(Motion.trailingPop)
            }
            primaryControl
        }
        .animation(Motion.quick, value: store.running)
        .animation(Motion.quick, value: hasDraft)
    }

    /// Smaller stop next to send while a draft is waiting; its 28 pt look keeps a 44 pt hit area without growing the row.
    private var secondaryStop: some View {
        Button(action: stopTurn) {
            Image(systemName: "stop.fill").font(.caption).foregroundStyle(Theme.textSecondary)
                .frame(width: 28, height: 28).overlay(Circle().stroke(Theme.hairline, lineWidth: 1.5))
                .padding(8)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(-8)
        .accessibilityLabel("Stop")
    }

    /// Stop / send / run share one circle. The plain button and the interrupt menu are both always mounted with the
    /// same glyph, so the glyph morphs in place in both and only the live one shows (switching it doesn't animate).
    private var primaryControl: some View {
        let glyph = actionGlyph
        let menuLive = store.running && hasDraft && !isShell
        return ZStack {
            primaryButton(glyph).actionLayer(live: !menuLive)
            interruptMenu(glyph).actionLayer(live: menuLive)
        }
    }

    private func primaryButton(_ glyph: ActionGlyph) -> some View {
        Button {
            if glyph == .stop { stopTurn() } else { send() }
        } label: {
            primaryGlyph(glyph)
        }
        .buttonStyle(.plain)
        .disabled(glyph.isDisabled)
        .accessibilityLabel(glyph == .stop ? Text("Stop") : (isShell ? Text("Run") : Text("Send")))
    }

    // tap: steer or queue; touch and hold: stop the turn and send now. A shortcut, not the only way:
    // Stop then Send is visible, the queue tray has the same button, and VoiceOver gets a named action.
    private func interruptMenu(_ glyph: ActionGlyph) -> some View {
        Menu {
            Button(action: interruptAndSend) { Label("Interrupt and Send", systemImage: "forward.end.fill") }
        } label: {
            primaryGlyph(glyph)
        } primaryAction: {
            send()
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .disabled(glyph.isDisabled)
        .accessibilityLabel(Text("Send"))
        .accessibilityAction(named: Text("Interrupt and Send"), interruptAndSend)
    }

    private func primaryGlyph(_ glyph: ActionGlyph) -> some View {
        Image(systemName: glyph.symbol)
            .font(glyph == .stop ? .caption.weight(.bold) : .body.weight(.bold))
            .foregroundStyle(glyphForeground(glyph))
            .contentTransition(.symbolEffect(.replace))
            .frame(width: 34, height: 34)
            .background(Circle().fill(glyphFill(glyph)))
            .scaleEffect(glyph.isDisabled ? 0.92 : 1)
            .animation(Motion.quick, value: glyph)
    }

    private func glyphForeground(_ glyph: ActionGlyph) -> Color {
        switch glyph {
        case .stop: return Theme.bgBase
        case .send: return Color.white
        case .disabled: return Theme.textTertiary
        }
    }

    private func glyphFill(_ glyph: ActionGlyph) -> Color {
        switch glyph {
        case .stop: return Theme.textPrimary
        case .send(let shell): return shell ? Theme.shell : Theme.accent
        case .disabled: return Theme.hairline
        }
    }

    private func stopTurn() {
        model.cancel(store)
        stopTick += 1
    }

    /// Stop the running turn and send the draft (plus anything queued) right after it.
    private func interruptAndSend() {
        guard canSend, !isShell else { return }
        withAnimation(Motion.layout) {
            sendDraft(interrupt: store.running)
            pinned = true
            position.scrollTo(edge: .bottom)
        }
        stopTick += 1
    }

    private func sendQueuedNow() {
        let interrupting = store.running
        withAnimation(Motion.layout) {
            model.sendQueued(store)
            pinned = true
            position.scrollTo(edge: .bottom)
        }
        if interrupting { stopTick += 1 } else { sendTick += 1 }
    }

    /// The draft clears, the message rises into the transcript and the scroll follows it in one motion.
    private func send() {
        guard canSend else { return }
        withAnimation(Motion.layout) {
            if isShell {
                model.runShell(store, command: shellCommand)
                draft = ""
            } else {
                sendDraft(interrupt: false)
            }
            pinned = true
            position.scrollTo(edge: .bottom)
        }
        sendTick += 1
    }

    /// Text, photos and files go out as one message; picked files are stored on the agent's host first and the
    /// prompt points at them. The composer keeps them (and shows why) if an upload fails.
    private func sendDraft(interrupt: Bool) {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let imgs = images
        guard !files.isEmpty else {
            model.send(store, text: text, images: imgs, interrupt: interrupt)
            draft = ""; images = []
            return
        }
        let picked = files
        uploading = true
        Task {
            defer { uploading = false }
            do {
                var links: [JSON] = []
                for f in picked { links.append(try await model.upload(f, to: store)) }
                withAnimation(Motion.layout) {
                    model.send(store, text: text, images: imgs, attachments: links, interrupt: interrupt && store.running)
                    if draft.trimmingCharacters(in: .whitespacesAndNewlines) == text { draft = "" }
                    if images == imgs { images = [] }
                    files.removeAll { f in picked.contains { $0.id == f.id } }
                    if pinned { position.scrollTo(edge: .bottom) }
                }
            } catch {
                store.error = error.localizedDescription
                Haptics.error()
            }
        }
    }

    // MARK: selector chips
    private var chipRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if let opt = store.modelOption { modelChip(opt) }
                if let opt = store.effortOption { effortChip(opt) }
                modeChip
                quotaChip
                contextChip
            }
            .padding(.horizontal, 2)
        }
        .scrollClipDisabled()
    }

    private func modelChip(_ opt: ConfigOption) -> some View {
        Button { showModelPicker = true } label: {
            HStack(spacing: 6) {
                ProviderIcon(provider: store.agent == "claude" ? .anthropic : Provider.of(opt.current.string), size: 15)
                Text(verbatim: shortModel(opt.currentName)).font(.footnote.weight(.medium)).lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 9, weight: .bold)).opacity(0.6)
            }
            .foregroundStyle(Theme.textPrimary)
            .padding(.horizontal, 12).frame(height: 32)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .capsule)
        .popover(isPresented: $showModelPicker) {
            ModelPicker(option: opt, agent: store.agent) { v in model.setConfig(store, option: opt, value: .string(v)); Haptics.selection() }
                .presentationCompactAdaptation(.popover)
        }
    }

    private func effortChip(_ opt: ConfigOption) -> some View {
        Button { showEffort = true } label: {
            HStack(spacing: 5) {
                Image(systemName: effortIcon(opt.current.string)).font(.caption.weight(.semibold))
                    .contentTransition(.symbolEffect(.replace))
                Text(verbatim: EffortNames.name(opt.current.string ?? "", fallback: opt.currentName)).font(.footnote.weight(.medium)).lineLimit(1)
            }
            .foregroundStyle(Theme.textPrimary)
            .padding(.horizontal, 12).frame(height: 32)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .capsule)
        .popover(isPresented: $showEffort) {
            EffortPopover(option: opt) { v in model.setConfig(store, option: opt, value: .string(v)) }
                .presentationCompactAdaptation(.popover)
        }
    }

    /// Remaining quota of the pool the selected model draws from (its tightest window).
    @ViewBuilder private var quotaChip: some View {
        let limit = model.quotas[store.agent]?.limiting(forModel: store.modelOption?.current.string, agent: store.agent)
        let pool = limit?.pool
        if model.isConnected {
            Button { showQuota = true } label: {
                HStack(spacing: 5) {
                    if let b = limit?.bucket, let f = b.fraction {
                        RingGauge(fraction: f, size: 13, lineWidth: 2, tint: f < 0.4 ? quotaTint(f) : Theme.accent)
                        Text(verbatim: "\(b.shortLabel) \(Int((f * 100).rounded()))%")
                            .font(.footnote.monospacedDigit()).foregroundStyle(f < 0.4 ? quotaTint(f) : Theme.textSecondary)
                    } else {
                        RingGauge(fraction: nil, size: 13, lineWidth: 2)
                        Text("Quota").font(.footnote).foregroundStyle(Theme.textSecondary)
                    }
                }
                .padding(.horizontal, 12).frame(height: 32)
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .capsule)
            .matchedTransitionSource(id: "quota", in: quotaNS)
            .accessibilityLabel(pool.map { p in Text("\(p.label) quota") } ?? Text("Quota"))
        }
    }

    /// Context-window usage, labelled so it isn't mistaken for quota.
    @ViewBuilder private var contextChip: some View {
        if let u = store.usage, u.size > 0 {
            let f = Double(u.used) / Double(u.size)
            Menu {
                Text("Context: \(compactNumber(u.used)) of \(compactNumber(u.size)) tokens")
            } label: {
                HStack(spacing: 5) {
                    Image(systemName: "text.alignleft").font(.caption)
                    Text(verbatim: "\(Int((f * 100).rounded()))%").font(.footnote.monospacedDigit())
                }
                .foregroundStyle(f > 0.8 ? Theme.attention : Theme.textSecondary)
                .padding(.horizontal, 12).frame(height: 32)
            }
            .glassEffect(.regular.interactive(), in: .capsule)
            .accessibilityLabel("Context \(compactNumber(u.used)) of \(compactNumber(u.size)) tokens")
        }
    }

    @ViewBuilder private var modeChip: some View {
        let modeChoices: [ConfigChoice] = store.modeOption?.choices ?? store.modes.map { ConfigChoice(value: $0.id, name: $0.name, description: nil, group: nil) }
        let current = store.autoApprove ? "turbo" : (store.modeOption?.current.string ?? store.currentMode ?? "default")
        let chipTint: Color = store.autoApprove ? Theme.turbo : (current == "plan" ? Theme.running : Theme.textPrimary)
        let glass: Glass = store.autoApprove ? Glass.regular.tint(Theme.turbo.opacity(0.45)).interactive() : Glass.regular.interactive()
        let chipText: String = ModeNames.name(current, fallback: modeChoices.first { $0.value == current }?.name ?? String(localized: "Mode"))
        if !modeChoices.isEmpty {
            Menu {
                Picker("Mode", selection: Binding(get: { current }, set: { v in
                    if v == "turbo" && !turboConfirmed { confirmTurbo = true } else { model.choose(mode: v, for: store) }
                })) {
                    ForEach(modeChoices) { c in Label(ModeNames.name(c.value, fallback: c.name), systemImage: modeIcon(c.value)).tag(c.value) }
                    Label("Turbo · approve all", systemImage: "bolt.fill").tag("turbo")
                }
            } label: {
                ChipLabel(icon: modeIcon(current), text: chipText)
            }
            .tint(chipTint)
            .glassEffect(glass, in: .capsule)
        }
    }

    private func shortModel(_ n: String) -> String {
        n.replacingOccurrences(of: "Gemini 4 ", with: "").replacingOccurrences(of: "Claude ", with: "").replacingOccurrences(of: "Gemini ", with: "")
    }
    private func effortIcon(_ v: String?) -> String {
        switch v {
        case "low": return "gauge.with.dots.needle.33percent"
        case "medium": return "gauge.with.dots.needle.50percent"
        case "high": return "gauge.with.dots.needle.67percent"
        default: return "gauge.with.dots.needle.100percent"
        }
    }
    private func modeIcon(_ v: String) -> String {
        switch v {
        case "accept-edits", "acceptEdits": return "pencil.line"
        case "bypassPermissions": return "exclamationmark.shield"
        case "auto": return "wand.and.sparkles"
        case "plan": return "list.bullet.clipboard"
        case "turbo": return "bolt.fill"
        default: return "hand.raised"
        }
    }

    // MARK: top banner for other sessions waiting on you
    @ViewBuilder private var otherSessionsBanner: some View {
        let others = model.pending.values.filter { $0.sessionId != store.sessionId }.sorted { $0.key < $1.key }
        if let first = others.first {
            Button { if let sid = first.sessionId { openSession(sid) } } label: {
                HStack(spacing: 10) {
                    Image(systemName: "hand.raised.fill").foregroundStyle(Theme.attention)
                    Group {
                        if others.count > 1 { Text("\(others.count) other conversations need you") } else { Text("Another conversation needs approval") }
                    }
                    .font(.subheadline.weight(.medium)).lineLimit(1)
                    Spacer(minLength: 4)
                    Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(Theme.textTertiary)
                }
                .padding(.horizontal, 16).frame(height: 40)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.tint(Theme.attention.opacity(0.35)).interactive(), in: .capsule)
            .padding(.horizontal, 16).padding(.bottom, 4)
            .transition(Motion.banner)
        }
    }

    private var moreMenu: some View {
        Menu {
            if let cwd = store.cwd {
                Button { UIPasteboard.general.string = cwd } label: { Label("Copy folder path", systemImage: "doc.on.doc") }
            }
            ForEach(store.otherOptions) { opt in
                if opt.type == "boolean" {
                    Button { model.setConfig(store, option: opt, value: .bool(!(opt.current.bool ?? false))) } label: {
                        Label(opt.name, systemImage: opt.current.bool == true ? "checkmark.square" : "square")
                    }
                } else if !opt.choices.isEmpty {
                    Picker(opt.name, selection: Binding(get: { opt.current.string ?? "" }, set: { model.setConfig(store, option: opt, value: .string($0)) })) {
                        ForEach(opt.choices) { c in Text(c.name).tag(c.value) }
                    }
                }
            }
            if let u = store.usage, u.size > 0 {
                Text("Context: \(compactNumber(u.used)) of \(compactNumber(u.size)) tokens")
            }
        } label: {
            Image(systemName: "ellipsis")
        }
    }
}

struct AnswerView: View {
    let item: MessageItem
    var live = false
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            MarkdownView(text: item.text, live: live)
            if !item.images.isEmpty || !item.files.isEmpty { MessageAttachments(images: item.images, files: item.files) }
        }
            .frame(maxWidth: .infinity, alignment: .leading)
            .messageMenu(item.text, markdown: true)
    }
}

/// Localized names for agy's mode / effort ids (agy sends English names).
enum ModeNames {
    static func name(_ id: String, fallback: String) -> String {
        switch id {
        case "default": return String(localized: "Default")
        case "accept-edits", "acceptEdits": return String(localized: "Accept edits")
        case "bypassPermissions": return String(localized: "Bypass permissions")
        case "auto": return String(localized: "Auto")
        case "plan": return String(localized: "Plan")
        case "turbo": return String(localized: "Turbo")
        default: return fallback
        }
    }
}

enum EffortNames {
    static func name(_ id: String, fallback: String) -> String {
        switch id {
        case "low": return String(localized: "Low")
        case "medium": return String(localized: "Medium")
        case "high": return String(localized: "High")
        case "xhigh": return String(localized: "Extra")
        case "max": return String(localized: "Max")
        case "default": return String(localized: "Default")
        default: return fallback
        }
    }

    static func blurb(_ id: String) -> String {
        switch id {
        case "default": return String(localized: "Use the model's own default.")
        case "low": return String(localized: "Fastest answers, light reasoning.")
        case "medium": return String(localized: "Balanced speed and depth.")
        case "high": return String(localized: "Thinks longer before acting. Good default for coding.")
        case "xhigh", "max": return String(localized: "Most thorough and slowest; uses more quota.")
        default: return ""
        }
    }
}

struct ChipLabel: View {
    let icon: String
    let text: String
    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: icon).font(.caption.weight(.semibold))
            Text(text).font(.footnote.weight(.medium)).lineLimit(1)
            Image(systemName: "chevron.down").font(.system(size: 9, weight: .bold)).opacity(0.6)
        }
        .padding(.horizontal, 12).frame(height: 32)
        .contentShape(Capsule())
    }
}

/// A glass capsule that opens a picker for one server-driven config option.
struct OptionChip: View {
    let icon: String
    let label: String
    let option: ConfigOption
    var nameFor: (ConfigChoice) -> String = { $0.name }
    var onSelect: (String) -> Void
    @State private var tick = 0

    var body: some View {
        Menu {
            Picker(option.name, selection: Binding(get: { option.current.string ?? "" }, set: { onSelect($0); tick += 1 })) {
                ForEach(option.choices) { c in Text(nameFor(c)).tag(c.value) }
            }
        } label: {
            ChipLabel(icon: icon, text: label)
        }
        .tint(Theme.textPrimary)
        .glassEffect(.regular.interactive(), in: .capsule)
        .haptic(.selection, trigger: tick)
    }
}

fileprivate extension View {
    /// One of the stacked primary-control layers: only the live one is visible, hit-testable and accessible.
    /// The switch itself never animates, so the two copies never cross-fade.
    func actionLayer(live: Bool) -> some View {
        self
            .animation(nil) { $0.opacity(live ? 1 : 0) }
            .allowsHitTesting(live)
            .accessibilityHidden(!live)
    }
}

struct IdentifiedPath: Identifiable { let path: String; var id: String { path } }

enum ImageTools {
    /// Downscale to max 1600px and re-encode as JPEG so prompts stay small.
    static func jpeg(_ data: Data, maxSide: CGFloat = 1600) -> Data? {
        guard let img = UIImage(data: data) else { return nil }
        let scale = min(1, maxSide / max(img.size.width, img.size.height))
        let size = CGSize(width: img.size.width * scale, height: img.size.height * scale)
        let fmt = UIGraphicsImageRendererFormat(); fmt.scale = 1
        let out = UIGraphicsImageRenderer(size: size, format: fmt).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
        return out.jpegData(compressionQuality: 0.8)
    }
}
