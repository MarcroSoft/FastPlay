import UIKit

/// A folder in Dropbox or on a server that is synced to this device by itself,
/// each time FastPlay starts.
struct AutoSyncFolder: Codable, Equatable {
    var id = UUID()
    /// Its source's id (FileSources).
    var sourceID: String
    var path: String
    var displayPath: String
    var name: String
    var lastSynced: Date?
    var lastOutcome: String?

    var folder: FileEntry {
        FileEntry(name: name, path: path, displayPath: displayPath, isFolder: true)
    }
}

/// The folders that sync by themselves, in the app's defaults, in the order added.
enum AutoSyncStore {
    private static let key = "AutoSyncFolders"

    static var all: [AutoSyncFolder] {
        get {
            guard let data = UserDefaults.standard.data(forKey: key) else { return [] }
            return (try? JSONDecoder().decode([AutoSyncFolder].self, from: data)) ?? []
        }
        set {
            UserDefaults.standard.set(try? JSONEncoder().encode(newValue), forKey: key)
        }
    }

    static func find(source: FileSource, path: String) -> AutoSyncFolder? {
        all.first { $0.sourceID == source.id && FileOperations.same($0.path, path) }
    }

    static func add(source: FileSource, folder: FileEntry) {
        guard find(source: source, path: folder.path) == nil else { return }
        all.append(AutoSyncFolder(sourceID: source.id, path: folder.path, displayPath: folder.displayPath, name: folder.name))
    }

    static func remove(_ folder: AutoSyncFolder) {
        all.removeAll { $0.id == folder.id }
    }

    /// The folders of a source that has gone (a server deleted).
    static func removeAll(sourceID: String) {
        all.removeAll { $0.sourceID == sourceID }
    }

    static func record(_ id: UUID, outcome: String) {
        var folders = all
        guard let index = folders.firstIndex(where: { $0.id == id }) else { return }
        folders[index].lastSynced = Date()
        folders[index].lastOutcome = outcome
        all = folders
    }
}

extension Notification.Name {
    /// Posted on the main thread when an automatic sync of every folder has finished.
    static let autoSyncDidFinish = Notification.Name("FPAutoSyncDidFinish")
    /// Posted on the main thread when AutoSync.status changes: every second or so
    /// while it syncs, and when it is done.
    static let autoSyncStatusDidChange = Notification.Name("FPAutoSyncStatusDidChange")
}

/// Syncing the folders that sync by themselves: when FastPlay starts, and when it
/// comes back to the front after half an hour or more away.
@MainActor
enum AutoSync {
    private static var running = false
    private static var lastRun: Date?

    /// Where an automatic sync has got, for the Home screen and Auto Sync.
    struct Status {
        /// Syncing now, or done.
        let running: Bool
        /// The folder being synced, while running.
        let folderID: UUID?
        /// "Music, 1 of 2 folders" while running; "Finished at 10:42" when done.
        let heading: String
        /// How the folder's sync is going (TransferProgress), or what each folder's did.
        let detail: String

        var text: String { detail.isEmpty ? heading : "\(heading)\n\(detail)" }
    }

    /// Nil until an automatic sync has run since FastPlay started.
    private(set) static var status: Status? {
        didSet { NotificationCenter.default.post(name: .autoSyncStatusDidChange, object: nil) }
    }

    static func runIfDue() {
        if let last = lastRun, Date().timeIntervalSince(last) < 30 * 60 { return }
        Task { await runAll() }
    }

    /// Syncs every folder, one after another, saying what happened through VoiceOver.
    /// Files here that are no longer there are deleted without asking: that is what
    /// the user chose for the folder.
    static func runAll(announce: Bool = true) async {
        let folders = AutoSyncStore.all
        guard !running, !folders.isEmpty else { return }
        running = true
        lastRun = Date()
        defer { running = false }
        if announce {
            UIAccessibility.post(notification: .announcement,
                                 argument: folders.count == 1 ? "Auto syncing \(folders[0].name)" : "Auto syncing \(folders.count) folders")
        }
        var summary: [String] = []
        for (index, folder) in folders.enumerated() {
            let heading = folders.count == 1 ? folder.name : "\(folder.name), \(index + 1) of \(folders.count) folders"
            status = Status(running: true, folderID: folder.id, heading: heading, detail: "Looking…")
            var outcome: String
            if let source = FileSources.source(id: folder.sourceID) {
                do {
                    outcome = try await FolderSync.run(source: source, entry: folder.folder, sync: true,
                                                       progress: { detail in
                                                           status = Status(running: true, folderID: folder.id,
                                                                           heading: heading, detail: detail)
                                                       }, confirmDeleting: nil) ?? "Stopped."
                } catch {
                    outcome = FolderSync.describe(error)
                }
            } else {
                outcome = "Its server is no longer added."
            }
            AutoSyncStore.record(folder.id, outcome: outcome)
            summary.append("\(folder.name): \(outcome)")
        }
        let time = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .short)
        status = Status(running: false, folderID: nil, heading: "Finished at \(time)",
                        detail: summary.joined(separator: "\n"))
        if announce {
            UIAccessibility.post(notification: .announcement, argument: "Auto sync done. " + summary.joined(separator: " "))
        }
        NotificationCenter.default.post(name: .autoSyncDidFinish, object: nil)
    }
}

/// Copying files from elsewhere into FastPlay's own files.
enum FolderSync {
    /// Copies a file, or a folder with everything in it that FastPlay plays, into
    /// FastPlay's own files, keeping the folders as they are there.
    ///
    /// Download leaves alone what is already here. Sync makes the folder here match
    /// the one there: files that changed are fetched again, and files here that are
    /// no longer there are deleted, after `confirmDeleting` says yes (with none
    /// given, without asking). `progress` is told where it has got, as it goes and
    /// every second: the file, how much of it has come and how fast, and how long
    /// the rest will take (TransferProgress). The outcome, in words; nil if deleting
    /// was refused, in which case nothing was done.
    @MainActor
    static func run(source: FileSource, entry: FileEntry, sync: Bool,
                    progress: @escaping @MainActor (String) -> Void,
                    confirmDeleting: (@MainActor (Int, String) async -> Bool)?) async throws -> String? {
        let transfer = TransferProgress(report: progress)
        return try await transfer.run {
            try await run(source: source, entry: entry, sync: sync, transfer: transfer, confirmDeleting: confirmDeleting)
        }
    }

    @MainActor
    private static func run(source: FileSource, entry: FileEntry, sync: Bool, transfer: TransferProgress,
                            confirmDeleting: (@MainActor (Int, String) async -> Bool)?) async throws -> String? {
        let engine = FPEngine.shared
        let manager = FileManager.default
        let documents = URL(fileURLWithPath: engine.documentsPath)
        let root = documents.appendingPathComponent(entry.name)

        // What is there, and where each file goes here
        var wanted: [(file: FileEntry, destination: URL)] = []
        var sizes: [String: Int64] = [:]  // by destination path
        if entry.isFolder {
            let prefix = entry.displayPath.count
            for item in try await source.listAll(path: entry.path)
            where !item.isFolder && engine.isPlayableFile(item.name) {
                // The path below the folder being fetched
                let relative = String(item.displayPath.dropFirst(prefix)).trimmingCharacters(
                    in: CharacterSet(charactersIn: "/"))
                let destination = root.appendingPathComponent(relative)
                wanted.append((item, destination))
                sizes[destination.standardizedFileURL.path] = item.size
            }
        } else {
            wanted.append((entry, documents.appendingPathComponent(entry.name)))
        }

        // Syncing: what is here that is no longer there
        var stale: [URL] = []
        if sync, entry.isFolder,
           let walker = manager.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey]) {
            for case let url as URL in walker {
                let isFile = (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile ?? false
                if isFile, sizes[url.standardizedFileURL.path] == nil { stale.append(url) }
            }
        }
        if !stale.isEmpty, let confirmDeleting {
            guard await confirmDeleting(stale.count, entry.name) else { return nil }
        }

        // What to fetch, first, so that the time left can be told
        var fetching: [(file: FileEntry, destination: URL)] = []
        var kept = 0
        for item in wanted {
            let destination = item.destination
            if manager.fileExists(atPath: destination.path) {
                // Download keeps what is here. Sync fetches it again if the
                // one there is another size, or changed after this copy was made.
                let attributes = try? manager.attributesOfItem(atPath: destination.path)
                let localSize = (attributes?[.size] as? NSNumber)?.int64Value ?? -1
                let localDate = attributes?[.modificationDate] as? Date ?? .distantPast
                let changed = (item.file.size > 0 && localSize != item.file.size)
                    || (item.file.modified.map { $0 > localDate.addingTimeInterval(1) } ?? false)
                if !sync || !changed {
                    kept += 1
                    continue
                }
            }
            fetching.append(item)
        }
        transfer.total = fetching.reduce(0) { $0 + max(0, $1.file.size) }

        var fetched = 0
        for (index, item) in fetching.enumerated() {
            try Task.checkCancellation()
            let destination = item.destination
            transfer.file("\(index + 1) of \(fetching.count): \(destination.lastPathComponent)", size: item.file.size)
            try manager.createDirectory(at: destination.deletingLastPathComponent(),
                                        withIntermediateDirectories: true)
            try await source.download(item.file, to: destination)
            // Dated as it is there, so a later sync can tell if it changed
            if let modified = item.file.modified {
                try? manager.setAttributes([.modificationDate: modified], ofItemAtPath: destination.path)
            }
            fetched += 1
        }

        var removed = 0
        for url in stale where (try? manager.removeItem(at: url)) != nil { removed += 1 }
        if removed > 0 { removeEmptyFolders(under: root) }

        if wanted.isEmpty && removed == 0 {
            return "Nothing there that FastPlay plays."
        }
        var parts = [fetched == 1 ? "1 file downloaded" : "\(fetched) files downloaded"]
        if kept > 0 { parts.append(sync ? "\(kept) up to date" : "\(kept) already here") }
        if removed > 0 {
            parts.append(removed == 1 ? "1 deleted from this device" : "\(removed) deleted from this device")
        }
        return parts.joined(separator: ", ") + "."
    }

    static func describe(_ error: Error) -> String {
        let nsError = error as NSError
        if (error as? URLError)?.code == .cancelled
            || (nsError.domain == NSCocoaErrorDomain && nsError.code == NSUserCancelledError) {
            return "Stopped."
        }
        return error.localizedDescription
    }

    /// Folders left with nothing in them after a sync deleted their files.
    static func removeEmptyFolders(under root: URL) {
        let manager = FileManager.default
        guard let walker = manager.enumerator(at: root, includingPropertiesForKeys: [.isDirectoryKey]) else { return }
        var folders: [URL] = []
        for case let url as URL in walker where (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true {
            folders.append(url)
        }
        // Deepest first, so a folder emptied of its empty folders goes too
        for folder in folders.sorted(by: { $0.path.count > $1.path.count }) {
            if ((try? manager.contentsOfDirectory(atPath: folder.path)) ?? ["x"]).isEmpty {
                try? manager.removeItem(at: folder)
            }
        }
    }
}
