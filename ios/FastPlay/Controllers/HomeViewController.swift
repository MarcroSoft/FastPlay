import UIKit

/// The first screen: where music comes from, the folders made favorites, and the
/// settings.
final class HomeViewController: FastPlayTableViewController {
    private struct Item {
        let title: String
        let detail: String
        let symbol: String
        let action: (HomeViewController) -> Void
    }

    private let places: [Item] = [
        Item(title: "Files", detail: "On this device, and in the Files app", symbol: "folder") {
            $0.show(BrowserViewController(source: LocalSource.shared))
        },
        Item(title: "Dropbox", detail: "Play from your Dropbox", symbol: "shippingbox") {
            $0.openDropbox()
        },
        Item(title: "Servers", detail: "FTP, SFTP and SMB servers", symbol: "server.rack") {
            $0.show(ServersViewController())
        },
        Item(title: "Radio", detail: "Saved stations and the station directories",
             symbol: "dot.radiowaves.left.and.right") {
            $0.show(RadioViewController())
        },
        Item(title: "Podcasts", detail: "Subscriptions and the podcast directory", symbol: "mic") {
            $0.show(PodcastsViewController())
        },
        Item(title: "Open Address", detail: "A stream, a file or a playlist on the internet", symbol: "link") {
            $0.navigationController?.pushViewController(AddressViewController(), animated: true)
        },
    ]

    private let settings = Item(title: "Settings", detail: "Effects, seeking and speech", symbol: "gearshape") {
        $0.show(SettingsViewController())
    }

    private var favorites: [Favorite] = []
    /// Free space, for the Files and Dropbox rows, once known
    private var space: [String: String] = [:]

    private var syncObserver: NSObjectProtocol?

    private enum Section { case autoSync, places, favorites, settings }
    private var sections: [Section] {
        var sections: [Section] = [.places]
        if AutoSync.status != nil { sections.append(.autoSync) }
        if !favorites.isEmpty { sections.append(.favorites) }
        sections.append(.settings)
        return sections
    }

    init() {
        super.init(style: .insetGrouped)
        title = "FastPlay"
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    deinit {
        if let syncObserver { NotificationCenter.default.removeObserver(syncObserver) }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        syncObserver = NotificationCenter.default.addObserver(forName: .autoSyncStatusDidChange, object: nil,
                                                              queue: .main) { [weak self] _ in
            self?.autoSyncChanged()
        }
    }

    /// The Auto Sync row brought up to date where it is, so VoiceOver stays where it was.
    private func autoSyncChanged() {
        guard isViewLoaded else { return }
        if sections.count == tableView.numberOfSections, let section = sections.firstIndex(of: .autoSync) {
            tableView.reconfigureRows(at: [IndexPath(row: 0, section: section)])
        } else {
            tableView.reloadData()
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        favorites = FavoritesStore.all
        tableView.reloadData()
        updateSpace()
    }

    /// "12.3 GB free of 128 GB", for this device and Dropbox (if connected).
    private func updateSpace() {
        Task { @MainActor in
            var sources: [FileSource] = [LocalSource.shared]
            if DropboxClient.shared.isSignedIn { sources.append(DropboxClient.shared) }
            for source in sources {
                guard let free = try? await source.storageSpace() else { continue }
                let text = ByteCountFormatter.string(fromByteCount: free.free, countStyle: .file) + " free of "
                    + ByteCountFormatter.string(fromByteCount: free.total, countStyle: .file)
                if space[source.title] != text {
                    space[source.title] = text
                    tableView.reloadData()
                }
            }
        }
    }

    private func show(_ controller: UIViewController) {
        navigationController?.pushViewController(controller, animated: true)
    }

    /// Dropbox, signing in first if FastPlay is not connected yet; then `then`.
    private func withDropbox(_ then: @escaping () -> Void) {
        if DropboxClient.shared.isSignedIn { return then() }
        Task { @MainActor in
            do {
                try await DropboxClient.shared.signIn(from: self)
                UIAccessibility.post(notification: .announcement, argument: "Connected to Dropbox")
                then()
            } catch DropboxError.cancelled {
                // Their choice: nothing to say
            } catch {
                tell("Dropbox", error.localizedDescription)
            }
        }
    }

    private func openDropbox() {
        withDropbox { [weak self] in self?.show(BrowserViewController(source: DropboxClient.shared)) }
    }

    private func open(_ favorite: Favorite) {
        guard let source = FileSources.source(id: favorite.sourceID) else {
            return tell(favorite.name, "The server it was on has been deleted.")
        }
        let browse: () -> Void = { [weak self] in
            self?.show(BrowserViewController(source: source, folder: favorite.folder, isTop: false))
        }
        if source.id == DropboxClient.shared.id { withDropbox(browse) } else { browse() }
    }

    /// What is on the clipboard pasted into FastPlay's own folder, from here.
    private func pasteIntoFiles() {
        Pasting.pasteClipboard(into: FPEngine.shared.documentsPath, of: LocalSource.shared, from: self) { [weak self] in
            self?.tableView.reloadData()
            self?.updateSpace()
        }
    }

    /// What is on the clipboard pasted into a favorite folder, from here.
    private func paste(into favorite: Favorite) {
        guard let source = FileSources.source(id: favorite.sourceID) else {
            return tell(favorite.name, "The server it was on has been deleted.")
        }
        let paste: () -> Void = { [weak self] in
            guard let self else { return }
            Pasting.pasteClipboard(into: favorite.folder.path, of: source, from: self) { [weak self] in
                self?.tableView.reloadData()
            }
        }
        if source.id == DropboxClient.shared.id { withDropbox(paste) } else { paste() }
    }

    /// Whether what is on the clipboard can be pasted into the favorite's folder.
    private func canPaste(into favorite: Favorite) -> Bool {
        !FileClipboard.items.isEmpty && FileSources.source(id: favorite.sourceID)?.canWrite == true
    }

    private func remove(_ favorite: Favorite) {
        FavoritesStore.remove(favorite)
        favorites = FavoritesStore.all
        tableView.reloadData()
        UIAccessibility.post(notification: .announcement, argument: "Removed \(favorite.name) from favorites")
    }

    private func tell(_ title: String, _ message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }

    // MARK: Table

    override func numberOfSections(in tableView: UITableView) -> Int { sections.count }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch sections[section] {
        case .autoSync: return 1
        case .places: return places.count
        case .favorites: return favorites.count
        case .settings: return 1
        }
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch sections[section] {
        case .autoSync: return "Auto Sync"
        case .favorites: return "Favorites"
        default: return nil
        }
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        var content = UIListContentConfiguration.subtitleCell()
        cell.accessibilityCustomActions = nil
        switch sections[indexPath.section] {
        case .autoSync:
            let status = AutoSync.status
            content.text = status?.running == true ? "Syncing" : "Done"
            content.secondaryText = status?.text
            content.secondaryTextProperties.numberOfLines = 0
            content.image = UIImage(systemName: "clock.arrow.2.circlepath")
        case .places, .settings:
            let item = sections[indexPath.section] == .settings ? settings : places[indexPath.row]
            content.text = item.title
            content.secondaryText = space[item.title].map { "\(item.detail). \($0)" } ?? item.detail
            content.image = UIImage(systemName: item.symbol)
            if item.title == "Files", !FileClipboard.items.isEmpty {
                cell.accessibilityCustomActions = [
                    UIAccessibilityCustomAction(name: "\(Pasting.title) Into FastPlay's Folder") { [weak self] _ in
                        self?.pasteIntoFiles()
                        return true
                    },
                ]
            }
        case .favorites:
            let favorite = favorites[indexPath.row]
            content.text = favorite.name
            content.secondaryText = FileSources.title(id: favorite.sourceID)
            content.image = UIImage(systemName: "star")
            var actions = [
                UIAccessibilityCustomAction(name: "Remove from Favorites") { [weak self] _ in
                    self?.remove(favorite)
                    return true
                },
            ]
            if canPaste(into: favorite) {
                actions.insert(UIAccessibilityCustomAction(name: "\(Pasting.title) Into Folder") { [weak self] _ in
                    self?.paste(into: favorite)
                    return true
                }, at: 0)
            }
            cell.accessibilityCustomActions = actions
        }
        cell.contentConfiguration = content
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        switch sections[indexPath.section] {
        case .autoSync: show(AutoSyncViewController())
        case .places: places[indexPath.row].action(self)
        case .favorites: open(favorites[indexPath.row])
        case .settings: settings.action(self)
        }
    }

    override func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath,
                            point: CGPoint) -> UIContextMenuConfiguration? {
        let pasteImage = UIImage(systemName: "doc.on.clipboard")
        switch sections[indexPath.section] {
        case .places where places[indexPath.row].title == "Files" && !FileClipboard.items.isEmpty:
            return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
                UIMenu(children: [
                    UIAction(title: "\(Pasting.title) Into FastPlay's Folder", image: pasteImage) { _ in
                        self?.pasteIntoFiles()
                    },
                ])
            }
        case .favorites:
            let favorite = favorites[indexPath.row]
            let pastable = canPaste(into: favorite)
            return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
                var children: [UIMenuElement] = []
                if pastable {
                    children.append(UIAction(title: "\(Pasting.title) Into Folder", image: pasteImage) { _ in
                        self?.paste(into: favorite)
                    })
                }
                children.append(UIAction(title: "Remove from Favorites", image: UIImage(systemName: "star.slash"),
                                         attributes: .destructive) { _ in self?.remove(favorite) })
                return UIMenu(children: children)
            }
        default:
            return nil
        }
    }

    override func tableView(_ tableView: UITableView,
                            trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        // VoiceOver lists swipe actions along with the row's own, which have these already
        if UIAccessibility.isVoiceOverRunning { return nil }
        guard sections[indexPath.section] == .favorites else { return nil }
        let favorite = favorites[indexPath.row]
        let remove = UIContextualAction(style: .destructive, title: "Remove") { [weak self] _, _, done in
            self?.remove(favorite)
            done(true)
        }
        return UISwipeActionsConfiguration(actions: [remove])
    }
}
