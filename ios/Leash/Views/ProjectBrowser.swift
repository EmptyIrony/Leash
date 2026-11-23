import SwiftUI

/// A conversation's folder on the agent's host, Files-style: folders and files push inside one sheet.
struct ProjectBrowser: View {
    @Environment(\.dismiss) private var dismiss
    let root: String
    var agent: String = "agy"
    var sessionId: String? = nil

    var body: some View {
        NavigationStack {
            ProjectFolder(path: root, agent: agent, sessionId: sessionId)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
        }
    }
}

private struct ProjectFolder: View {
    @Environment(AppModel.self) private var model
    let path: String
    let agent: String
    let sessionId: String?
    @State private var result: BrowseResult?
    @State private var error: String?

    var body: some View {
        List {
            if let r = result {
                ForEach(r.dirs, id: \.self) { d in
                    NavigationLink { ProjectFolder(path: Self.child(r, d), agent: agent, sessionId: sessionId) } label: {
                        Label(d, systemImage: "folder")
                    }
                }
                ForEach(r.files, id: \.self) { f in
                    NavigationLink { FileViewer(path: Self.child(r, f), agent: agent, sessionId: sessionId, embedded: true) } label: {
                        Label(f, systemImage: "doc")
                    }
                }
            }
        }
        .overlay {
            if let error {
                ContentUnavailableView("Can't open folder", systemImage: "folder.badge.questionmark", description: Text(error))
            } else if let r = result {
                if r.dirs.isEmpty && r.files.isEmpty { ContentUnavailableView("Empty Folder", systemImage: "folder") }
            } else {
                ProgressView()
            }
        }
        .navigationTitle((path as NSString).lastPathComponent)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard result == nil else { return }
            do { result = try await model.browse(path, agent: agent) } catch { self.error = error.localizedDescription }
        }
    }

    private static func child(_ r: BrowseResult, _ name: String) -> String { (r.path as NSString).appendingPathComponent(name) }
}
