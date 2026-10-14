# FastPlay for Android

The same app as the iPhone's (`ios/`), screen for screen: the same places, the
same player with its sliders and touch mode, the same settings, worded the same.
It is a port, not a new design. Where Android differs, the difference is the
least it can be, and it is noted where it is made. YouTube is Android's alone.

## Building

1. `download-deps.sh` (or `download-deps.bat`) once, for SQLite and the tempo
   libraries in `deps/`.
2. FFmpeg for Android in `ffmpeg-android/` at the repository's top: CI's latest
   (`gh run download <run> --name ffmpeg-android --dir ffmpeg-android`, from the
   Android workflow), or `ANDROID_NDK_HOME=... ci/ffmpeg/build.sh android ffmpeg-android`
   on Linux or macOS.
3. `android/local.properties` with `sdk.dir=` your Android SDK.
4. `cd android && ./gradlew assembleDebug` (NDK 27.3.13750724, CMake 3.22.1).

CI (`.github/workflows/android.yml`) builds the release APK on every push to
master that touches it and puts it on the `latest` release as `FastPlay.apk`.

## How it is put together

| iPhone (`ios/FastPlay`) | Android (`app/src/main/java/me/masonasons/fastplay`) |
|---|---|
| `Bridge/FPEngine.mm`, `FPSettings.mm`, `FPSources.mm`, `FPRemote.mm` | `src/main/cpp/*_jni.cpp`, reached through `engine/Native.kt` |
| `FPEngine` (Swift's view of it) | `engine/Engine.kt` (`Engine.playFile`, `Engine.title`, `Engine.number(...)`) |
| `FPEngine (Sources)` | `engine/Sources.kt` (radio, podcasts; lookups are `suspend`) |
| `src/platform/*_ios.mm` (paths, speech, HTTP) | `src/main/cpp/android_platform.cpp` + `engine/Callbacks.kt` |
| AVAudioSession, MPNowPlayingInfoCenter | `playback/PlaybackController.kt`, `PlaybackService.kt` |
| `UINavigationController` | `MainActivity` (one activity, a back stack of `Screen`s) |
| `UIViewController` | `ui/Screen.kt` (a Fragment: `title`, `toolbarItems()`, `bottomBar()`) |
| `FastPlayTableViewController`, `UITableView` | `ui/ListScreen.kt` (`rows()` returns `ListRow`s; `reload()`) |
| `UIAlertController` | `ui/Dialogs.kt` (`tell`, `confirm`, `choose`, `actions`, `prompt`) |
| `FileTransfer` (document pickers) | `ui/FileTransfer.kt`, `MainActivity.pickFile/pickFileCopy/saveFile` |
| `UserDefaults` | `Engine.prefs()` (SharedPreferences) |
| `Task { @MainActor in ... }` | `viewLifecycleOwner.lifecycleScope.launch { ... }` |

### Screens and lists

A screen is a `Screen`; most are a `ListScreen`, whose `rows()` gives the table:
`ListRow.Header`, `ListRow.Footer` and `ListRow.Item`. An item has a title, a
subtitle or a value, an icon, a checkmark (`checked`), an arrow to another
screen (`disclosure`), a switch (`toggle`/`onToggle`), and `actions`.

- A cell's context menu, its swipe actions and its VoiceOver custom actions are
  all one list on Android: `actions` (`RowAction`). They are offered on a long
  press and in TalkBack's actions menu, by name, in the iPhone's order.
- `description` replaces what TalkBack reads, as `accessibilityLabel` does.
- `hint` is the `accessibilityHint`.
- Give an item an `id` when its title can repeat or change, so a reload keeps
  its place and TalkBack's focus.
- A screen with a selection mode (the browser) shows its own bar at the bottom
  with `bottomBar()` (the iPhone's `ownToolbarItems`), and calls
  `refreshChrome()` when it changes.

### Toolbar

`toolbarItems()` is the navigation bar's buttons: a `ToolbarItem` with an icon
is an icon button labelled with its title; one with a `submenu` opens a menu
(the iPhone's `UIMenu` bar buttons). Call `refreshChrome()` after the items
change.

### Speech and announcements

- What the engine says (`Engine.speak`, and the core's own speech) goes to
  TalkBack as an announcement, as it goes to VoiceOver on the iPhone.
- What a screen says of its own (`UIAccessibility.post(.announcement, ...)` on
  the iPhone) is `announce(text)` on a `Screen`.

### Words

The iPhone's text is kept word for word: titles, labels, hints, footers,
messages, errors. "VoiceOver" becomes "TalkBack", "the Files app" becomes
"the system's file picker" (or what fits), "iPhone" becomes "phone" or "device".

### Files

`files/FileSource.kt` is `FileSource`, `FileEntry`, `RemoteError` and
`FileSources`; `LocalSource` is the device's shared storage (all files access),
and FastPlay's own folder (`Engine.documentsPath`, `/storage/emulated/0/FastPlay`)
is where recordings and downloads go, as the iPhone's Documents folder.

### Direct touch

The player's touch area (`ui/DirectTouchView.kt`) works with BackTalk's direct
touch: see `ui/DirectTouchGate.kt`. Under plain TalkBack its commands are its
actions in TalkBack's actions menu.
