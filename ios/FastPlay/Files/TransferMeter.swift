import Foundation

/// The bytes a copy has moved so far, counted by the sources as they read and
/// write (FileSource's download and upload), for the speed shown while copying.
/// The one for the work under way is `current`, a task-local, so the sources need
/// no argument for it; work a source hands to another thread takes it along.
final class TransferMeter: @unchecked Sendable {
    @TaskLocal static var current: TransferMeter?

    private let lock = NSLock()
    private var counted: Int64 = 0
    private var tasks: [URLSessionTask] = []

    /// All the bytes so far, with those of the URLSession transfers under way.
    var bytes: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return tasks.reduce(counted) { $0 + $1.countOfBytesReceived + $1.countOfBytesSent }
    }

    func add(_ count: Int64) {
        guard count > 0 else { return }
        lock.lock()
        counted += count
        lock.unlock()
    }

    func add(_ count: Int) { add(Int64(count)) }

    /// A URLSession transfer, whose bytes count as they go, until `finish`.
    func watch(_ task: URLSessionTask) {
        lock.lock()
        tasks.append(task)
        lock.unlock()
    }

    func finish(_ task: URLSessionTask) {
        lock.lock()
        if let index = tasks.firstIndex(where: { $0 === task }) {
            tasks.remove(at: index)
            counted += task.countOfBytesReceived + task.countOfBytesSent
        }
        lock.unlock()
    }

    /// For a library that tells how far into a file it has got, rather than how
    /// much more came: `reached` with each of those counts.
    final class Running: @unchecked Sendable {
        private let meter: TransferMeter?
        private let lock = NSLock()
        private var last: Int64 = 0

        init(_ meter: TransferMeter?) { self.meter = meter }

        func reached(_ total: Int64) {
            lock.lock()
            let more = total - last
            last = max(last, total)
            lock.unlock()
            meter?.add(more)
        }
    }
}

/// URLSession's delegate for one transfer, which has the meter count it.
final class MeteredTask: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let meter: TransferMeter?
    private var task: URLSessionTask?

    init(_ meter: TransferMeter?) { self.meter = meter }

    func urlSession(_ session: URLSession, didCreateTask task: URLSessionTask) {
        self.task = task
        meter?.watch(task)
    }

    /// The transfer is over, however it went.
    func finish() {
        if let task { meter?.finish(task) }
    }
}

/// What a copy is doing, in words brought up to date every second: the file, how
/// much of it has come, how fast, and when the whole size is known, how long the
/// rest will take.
///
///     3 of 12: Song.mp3
///     4.2 MB of 10.1 MB, 1.8 MB a second
///     45 MB of 300 MB in all, about 2 minutes left
@MainActor
final class TransferProgress {
    let meter = TransferMeter()
    /// All the bytes the copy will move, for the time left, when known.
    var total: Int64?

    private let report: (String) -> Void
    private var heading = ""
    private var stepName: String?
    private var fileSize: Int64 = 0
    private var fileStart: Int64 = 0
    private var counted = true
    // Recent (seconds, bytes) readings, for the speed
    private var readings: [(time: TimeInterval, bytes: Int64)] = []

    init(report: @escaping (String) -> Void) {
        self.report = report
    }

    /// Runs `work` with its bytes counted, saying where it has got every second.
    func run<T>(_ work: () async throws -> T) async rethrows -> T {
        let ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                self?.update()
            }
        }
        defer { ticker.cancel() }
        return try await TransferMeter.$current.withValue(meter) { try await work() }
    }

    /// A file starting: "3 of 12: Song.mp3", `size` bytes of it to come. A file
    /// copied within this device isn't `counted`, and only its name is shown.
    func file(_ heading: String, size: Int64, counted: Bool = true) {
        self.heading = heading
        fileSize = size
        self.counted = counted
        stepName = nil
        fileStart = meter.bytes
        update()
    }

    /// The file's download or upload starting ("Downloading", "Uploading"), each
    /// counted from nothing.
    func step(_ name: String) {
        stepName = name
        fileStart = meter.bytes
        update()
    }

    private func update() {
        guard !heading.isEmpty else { return }  // still looking
        let bytes = meter.bytes
        let now = ProcessInfo.processInfo.systemUptime
        readings.append((now, bytes))
        readings.removeAll { now - $0.time > 5 }
        var speed: Double?
        if let first = readings.first, now - first.time >= 1 {
            speed = Double(bytes - first.bytes) / (now - first.time)
        }

        var lines = [heading]
        if counted {
            let done = max(0, bytes - fileStart)
            var line = stepName.map { "\($0): " } ?? ""
            line += fileSize > 0 ? "\(Self.size(min(done, fileSize))) of \(Self.size(fileSize))" : Self.size(done)
            if let speed { line += ", \(Self.size(Int64(speed))) a second" }
            lines.append(line)
        }
        if let total, total > 0 {
            var line = "\(Self.size(min(bytes, total))) of \(Self.size(total)) in all"
            if let speed, speed > 0, total > bytes {
                line += ", about \(Self.duration(Double(total - bytes) / speed)) left"
            }
            lines.append(line)
        }
        report(lines.joined(separator: "\n"))
    }

    static func size(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    /// "40 seconds", "2 minutes", "1 hour, 5 minutes"
    static func duration(_ seconds: Double) -> String {
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = .full
        formatter.maximumUnitCount = 2
        formatter.allowedUnits = seconds < 60 ? [.second] : [.hour, .minute]
        // Under a minute, to the nearest 5 seconds, so it doesn't count down every second
        let rounded = seconds < 60 ? max(5, (seconds / 5).rounded() * 5) : max(60, (seconds / 60).rounded() * 60)
        return formatter.string(from: rounded) ?? ""
    }
}
