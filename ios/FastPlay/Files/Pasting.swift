import UIKit

/// Pasting into a folder, with a progress alert that can stop it and the question
/// about names already taken: the browser's paste, and Home's Paste Into
/// FastPlay's Folder.
@MainActor
enum Pasting {
    /// "Paste Song.mp3", "Paste 3 Items"
    static var title: String {
        FileClipboard.items.count == 1 ? "Paste \(FileClipboard.items[0].entry.name)"
                                       : "Paste \(FileClipboard.items.count) Items"
    }

    /// Moves (after Cut) or copies (after Copy) what is on the clipboard into
    /// `folder` of `destination`, saying so when done. `finished` is told after,
    /// however it went.
    static func pasteClipboard(into folder: String, of destination: FileSource, from controller: UIViewController,
                               finished: @escaping () -> Void = {}) {
        let items = FileClipboard.items
        guard !items.isEmpty else { return }
        let move = FileClipboard.isCut
        run(items, move: move, into: folder, of: destination, title: move ? "Moving" : "Pasting",
            from: controller) { done in
            defer { finished() }
            guard done else { return }
            if move { FileClipboard.set([], cut: false) }  // moved: nothing left to paste
            UIAccessibility.post(notification: .announcement,
                                 argument: items.count == 1 ? "Pasted \(items[0].entry.name)" : "Pasted \(items.count) items")
        }
    }

    /// Copies or moves `items` into `folder` of `destination`, with a progress
    /// alert that can stop it, and the question about names already taken. `done`
    /// is told whether it all went.
    static func run(_ items: [FileClipboard.Item], move: Bool, into folder: String, of destination: FileSource,
                    title: String, from controller: UIViewController, done: @escaping (Bool) -> Void) {
        let progress = UIAlertController(title: title, message: "Starting…", preferredStyle: .alert)
        let state = ProgressState()
        let task = Task { @MainActor [weak controller] in
            let operations = FileOperations(move: move, resolver: { [weak controller] pasting, existing in
                guard let controller else { return nil }
                // The progress is in the way: aside while asking
                state.paused = true
                await UIViewController.dismiss(progress)
                let choice = await ConflictViewController.ask(from: controller, pasting: pasting, existing: existing)
                state.paused = false
                if choice != nil, !Task.isCancelled { controller.present(progress, animated: false) }
                return choice
            }, progress: { progress.message = $0 })
            var problem: String?
            do {
                try await operations.paste(items, into: destination, folder: folder)
            } catch is CancellationError {
                problem = "Stopped."
            } catch is FileOperations.Stopped {
                problem = "Stopped."
            } catch {
                problem = FolderSync.describe(error)
            }
            state.finished = true
            await UIViewController.dismiss(progress)
            if let problem, let controller {
                let alert = UIAlertController(title: title == "Moving" ? "Move" : "Paste", message: problem,
                                              preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "OK", style: .default))
                controller.present(alert, animated: true)
            }
            done(problem == nil)
        }
        progress.addAction(UIAlertAction(title: "Stop", style: .cancel) { _ in task.cancel() })
        controller.showSoon(progress, state)
    }
}

/// Where a piece of work with a progress alert has got to.
final class ProgressState {
    var finished = false
    var paused = false  // something else is being asked meanwhile
}

extension UIViewController {
    /// Shows `progress` once the work has taken more than a moment: quick work
    /// flashes nothing, and nothing is dismissed while it is still appearing.
    func showSoon(_ progress: UIAlertController, _ state: ProgressState) {
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard let self, !state.finished, !state.paused, progress.presentingViewController == nil,
                  self.presentedViewController == nil else { return }
            self.present(progress, animated: false)
        }
    }

    /// Dismisses `controller`, if it is showing, and waits until it has gone.
    static func dismiss(_ controller: UIViewController) async {
        guard controller.presentingViewController != nil else { return }
        await withCheckedContinuation { continuation in
            controller.dismiss(animated: false) { continuation.resume() }
        }
    }
}
