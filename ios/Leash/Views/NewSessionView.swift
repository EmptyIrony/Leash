import SwiftUI

struct NewSessionView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var onCreated: (String) -> Void
    @AppStorage("lastNewAgent") private var agent = "agy"
    @State private var creating: String?
    @State private var error: String?

    private var agentIds: [String] { model.agents.isEmpty ? ["agy"] : model.agents.map(\.id) }

    var body: some View {
        NavigationStack {
            List {
                if agentIds.count > 1 {
                    Section {
                        Picker("Agent", selection: $agent) {
                            ForEach(agentIds, id: \.self) { id in Text(verbatim: model.agentLabel(id)).tag(id) }
                        }
                        .pickerStyle(.segmented)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets())
                    }
                }
                if let error { Section { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(Theme.danger) } }
                if !model.isReady(agent) {
                    Section { Label("\(model.agentLabel(agent)) is not running", systemImage: "bolt.horizontal.circle").foregroundStyle(Theme.attention) }
                }
                let recent = model.recentFolders(agent: agent)
                if !recent.isEmpty {
                    Section("Recent folders") {
                        ForEach(recent, id: \.self) { f in
                            Button { start(f) } label: { folderRow(f) }.disabled(creating != nil || !model.isReady(agent))
                        }
                    }
                }
                Section {
                    NavigationLink { FolderBrowser(path: nil, agent: agent, pick: start) } label: {
                        Label(agent == "agy" ? "Browse the Mac…" : "Browse \(model.agentLabel(agent)) host…", systemImage: "folder.badge.gearshape")
                    }
                }
            }
            .navigationTitle("New Conversation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .overlay {
                if creating != nil {
                    HStack(spacing: 10) { ActivitySpinner(size: 18); Text("Starting…") }
                        .padding(.horizontal, 18).padding(.vertical, 12)
                        .glassEffect(.regular, in: .capsule)
                }
            }
            .onAppear { if !agentIds.contains(agent) { agent = agentIds.first ?? "agy" } }
        }
    }

    private func folderRow(_ f: String) -> some View {
        HStack {
            AgentIcon(agent: agent, size: 16)
            VStack(alignment: .leading) {
                Text(verbatim: (f as NSString).lastPathComponent).foregroundStyle(Theme.textPrimary)
                Text(verbatim: model.shortPath(f, agent: agent)).font(.caption).foregroundStyle(Theme.textSecondary).lineLimit(1).truncationMode(.head)
            }
            Spacer()
            if creating == f { ActivitySpinner() }
        }
    }

    private func start(_ cwd: String) {
        creating = cwd
        error = nil
        let a = agent
        Task {
            do { let id = try await model.newSession(cwd: cwd, agent: a); creating = nil; Haptics.impact(.medium); onCreated(id) }
            catch { self.error = error.localizedDescription; creating = nil; Haptics.error() }
        }
    }
}

struct FolderBrowser: View {
    @Environment(AppModel.self) private var model
    let path: String?
    var agent: String = "agy"
    var pick: (String) -> Void
    @State private var result: BrowseResult?
    @State private var error: String?

    var body: some View {
        List {
            if let error { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(Theme.danger) }
            if let r = result {
                Section {
                    Button { pick(r.path) } label: {
                        Label("Start conversation here", systemImage: "plus.bubble.fill").font(.headline)
                    }
                } footer: { Text(verbatim: model.shortPath(r.path, agent: agent)) }
                Section("Folders") {
                    ForEach(r.dirs, id: \.self) { d in
                        NavigationLink { FolderBrowser(path: (r.path as NSString).appendingPathComponent(d), agent: agent, pick: pick) } label: {
                            Label(d, systemImage: "folder")
                        }
                    }
                    if r.dirs.isEmpty { Text("No subfolders").foregroundStyle(Theme.textSecondary) }
                }
                if !r.files.isEmpty {
                    Section("\(r.files.count) files") {
                        ForEach(r.files.prefix(30), id: \.self) { f in Label(f, systemImage: "doc").foregroundStyle(Theme.textSecondary) }
                    }
                }
            } else if error == nil {
                ActivitySpinner(size: 22).frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(result.map { ($0.path as NSString).lastPathComponent } ?? model.agentLabel(agent))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if result?.isGit == true { ToolbarItem(placement: .primaryAction) { Label("git", systemImage: "arrow.triangle.branch").labelStyle(.titleAndIcon).font(.caption) } }
        }
        .task {
            do { result = try await model.browse(path, agent: agent) } catch { self.error = error.localizedDescription }
        }
    }
}
