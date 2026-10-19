import UIKit

/// Keep Screen On: while FastPlay is in front, the screen does not dim and lock
/// by itself. Off unless turned on.
@MainActor
enum KeepScreenOn {
    static var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: "KeepScreenOn") }
        set {
            UserDefaults.standard.set(newValue, forKey: "KeepScreenOn")
            apply()
        }
    }

    /// iOS keeps to it only while FastPlay is in front, so this is all there is to it.
    static func apply() {
        UIApplication.shared.isIdleTimerDisabled = enabled
    }
}
