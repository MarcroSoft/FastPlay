// FastPlay's engine for the Android app (Native.kt, Engine.kt): the iPhone's
// FPEngine.mm, through JNI. This is also where the core's calls to "the UI"
// (app_ui.h) arrive, which on the desktop is the main window.
//
// Everything here is called on the app's main thread, as FPEngine's methods are.

#include "android_jni.h"

#include "accessibility.h"
#include "app_ui.h"
#include "audio.h"
#include "commands.h"
#include "database.h"
#include "effects.h"
#include "globals.h"
#include "paths.h"
#include "platform.h"
#include "player.h"
#include "playlist_io.h"
#include "scheduler.h"
#include "settings.h"
#include "utils.h"
#include "version.h"

#include <algorithm>
#include <cstdio>
#include <string>
#include <vector>

// android_platform.cpp: what FastPlay says is dropped while this is set
void SetSpeechMuted(bool muted);
bool IsSpeechMuted();

using namespace fp;

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_me_masonasons_fastplay_engine_Native_##name

namespace {

// The seek unit that is "1 chapter" (settings.cpp)
const int kChapterSeekIndex = 12;
// Effects are numbered for the app: the four stream effects, then the DSP effects.
const int kDspEffectBase = 100;

bool g_started = false;
bool g_stateChangePending = false;

// Tell the app that something changed, once however many times it is asked for
// before the main thread gets to it.
void PostStateChange() {
    if (g_stateChangePending) return;
    g_stateChangePending = true;
    PostToUi([]() {
        g_stateChangePending = false;
        JNIEnv* env = Env();
        static jmethodID changed = CallbackMethod("stateChanged", "()V");
        env->CallStaticVoidMethod(Callbacks(), changed);
        CheckException(env, "stateChanged");
    });
}

void CallVoid(const char* name) {
    JNIEnv* env = Env();
    env->CallStaticVoidMethod(Callbacks(), CallbackMethod(name, "()V"));
    CheckException(env, name);
}

void StartPlaylistAt(int index) {
    g_currentTrack = -1;
    if (g_playlist.empty()) {
        Speak("Nothing to play");
        return;
    }
    PlayTrack(std::clamp(index, 0, static_cast<int>(g_playlist.size()) - 1));
    PostStateChange();
}

// As the desktop's Left and Right do (MainFrame::SeekBackOrForward)
void SeekStep(int direction) {
    const int dir = direction < 0 ? -1 : 1;
    if (g_currentSeekIndex == kChapterSeekIndex) {
        if (!g_chapters.empty()) {
            if (dir < 0) SeekToPrevChapter(); else SeekToNextChapter();
        }
    } else if (g_seekAmounts[g_currentSeekIndex].isTrack && g_playlist.size() <= 1) {
        // Track seeking with a single track: the first enabled time amount instead
        for (int i = 0; i < g_seekAmountCount; i++) {
            if (g_seekEnabled[i] && !g_seekAmounts[i].isTrack) {
                Seek(dir * g_seekAmounts[i].value);
                break;
            }
        }
    } else if (g_seekAmounts[g_currentSeekIndex].isTrack) {
        SeekTracks(dir * static_cast<int>(g_seekAmounts[g_currentSeekIndex].value));
    } else {
        Seek(dir * GetCurrentSeekAmount());
    }
    PostStateChange();
}

void ChangeSeekUnit(int direction) {
    const int dir = direction < 0 ? -1 : 1;
    if (IsScrubSeekMode()) ChangeScrubSpeed(dir); else CycleSeekAmount(dir);
    PostStateChange();
}

// A command, as the desktop's menu and hotkeys run them: the ones that make sense
// without windows of their own.
void RunCommand(int command, int param) {
    switch (command) {
        case IDM_PLAY_PLAYPAUSE: PlayPause(); break;
        case IDM_PLAY_PLAY: Play(); break;
        case IDM_PLAY_PAUSE: Pause(); break;
        case IDM_PLAY_STOP: Stop(); break;
        case IDM_PLAY_PREV: PrevTrack(); break;
        case IDM_PLAY_NEXT: NextTrack(param == 0); break;  // 1: load without playing
        case IDM_PLAY_SEEKBACK: SeekStep(-1); break;
        case IDM_PLAY_SEEKFWD: SeekStep(1); break;
        case IDM_PLAY_BEGINNING: SeekToPosition(0); break;
        case IDM_SEEK_DECREASE: ChangeSeekUnit(-1); break;
        case IDM_SEEK_INCREASE: ChangeSeekUnit(1); break;
        case IDM_SEEK_MODE: CycleSeekMode(); break;
        case IDM_PLAY_GOLIVE: GoLive(); break;
        case IDM_PLAY_MUTE: ToggleMute(); break;
        case IDM_PLAY_REPEAT_TOGGLE: ToggleRepeatMode(); break;
        case IDM_EFFECT_PREV: CycleParam(-1); break;
        case IDM_EFFECT_NEXT: CycleParam(1); break;
        case IDM_EFFECT_UP: AdjustCurrentParam(1); break;
        case IDM_EFFECT_DOWN: AdjustCurrentParam(-1); break;
        case IDM_EFFECT_RESET: ResetCurrentParam(); break;
        case IDM_RECORD_TOGGLE: ToggleRecording(); break;
        default: break;
    }
    PostStateChange();
}

// On a phone, choosing a file plays on through its folder unless told otherwise
// (the desktop's default is the file alone); set once, then the user's to change
void ApplyPhoneDefaults() {
    const std::string marker = WideToUtf8(GetDataDirectory()) + "phone-defaults";
    if (FILE* file = fopen(marker.c_str(), "r")) {
        fclose(file);
        return;
    }
    g_loadFolder = true;
    SaveSettings();
    if (FILE* file = fopen(marker.c_str(), "w")) fclose(file);
}

// Strings in threes (or more), for lists the app builds its objects from
jobjectArray Flat(JNIEnv* env, const std::vector<std::wstring>& items) {
    return ToJavaArray(env, items);
}

}  // namespace

// ---------------------------------------------------------------------------
// Starting and stopping
// ---------------------------------------------------------------------------

// Loads the settings and starts the audio engine. False if audio could not start.
JNI(jboolean, start)(JNIEnv*, jclass) {
    if (g_started) return JNI_TRUE;
    srand(static_cast<unsigned>(time(nullptr)));  // so the shuffle order differs between runs
    PlatformStartup();
    LoadSettings();
    // Recordings go in a folder of FastPlay's own files
    g_recordPath = GetUserMusicDir() + L"/Recordings";
    ApplyPhoneDefaults();
    if (!InitAudio()) return JNI_FALSE;
    InitDatabase();
    InitEffects();
    LoadDSPSettings();
    InitSpeech();
    g_started = true;
    // The app asks for the sound (audio focus) as the output starts
    audio::SetBeforeDeviceStart([]() { CallVoid("beforeDeviceStart"); });
    // What was playing last time, where it was
    if (g_playlist.empty()) LoadPlaybackState();
    PostStateChange();
    return JNI_TRUE;
}

// Saves the settings, the playlist and where playback is (going to the background).
JNI(void, saveState)(JNIEnv*, jclass) {
    if (!g_started) return;
    SaveCurrentPosition();
    SavePlaybackState();
    SaveSettings();
}

JNI(jboolean, ensureDeviceRunning)(JNIEnv*, jclass) {
    return audio::EnsureDeviceRunning() ? JNI_TRUE : JNI_FALSE;
}

JNI(void, checkScheduledEvents)(JNIEnv*, jclass) { CheckScheduledEvents(); }
JNI(void, durationTimerFired)(JNIEnv*, jclass) { HandleScheduledDurationEnd(); }
JNI(void, runCommand)(JNIEnv*, jclass, jint command, jint param) { RunCommand(command, param); }

// ---------------------------------------------------------------------------
// Files
// ---------------------------------------------------------------------------

JNI(jstring, documentsPath)(JNIEnv* env, jclass) { return ToJava(env, GetUserMusicDir()); }

JNI(jboolean, isPlayableFile)(JNIEnv* env, jclass, jstring path) {
    std::wstring wide = ToWide(env, path);
    if (IsPlaylistFile(wide)) return JNI_TRUE;
    size_t dot = wide.find_last_of(L'.');
    return dot != std::wstring::npos && IsSupportedAudioExt(wide.substr(dot)) ? JNI_TRUE : JNI_FALSE;
}

// Plays a file, with the other files of its folder as the playlist.
JNI(void, playFile)(JNIEnv* env, jclass, jstring path) {
    std::wstring wide = ToWide(env, path);
    int start = 0;
    if (IsPlaylistFile(wide)) {
        g_playlist = ParsePlaylist(wide);
    } else if (g_loadFolder) {
        start = ExpandFileToFolder(wide, g_playlist);  // with the rest of its folder
    } else {
        g_playlist.assign(1, wide);
    }
    StartPlaylistAt(start);
}

// Plays everything in a folder and the folders inside it.
JNI(void, playFolder)(JNIEnv* env, jclass, jstring path) {
    std::vector<std::wstring> files;
    AddFilesFromFolder(ToWide(env, path), files);
    g_playlist = files;
    StartPlaylistAt(0);
}

// Plays an address (a stream, a file on a server). `name` is what to call it.
JNI(void, playUrl)(JNIEnv* env, jclass, jstring url, jstring name) {
    std::wstring wide = ToWide(env, url);
    std::wstring shown = ToWide(env, name);
    if (!shown.empty()) SetTrackName(wide, shown);
    g_playlist.assign(1, wide);
    StartPlaylistAt(0);
}

// Plays several addresses as a playlist, each under its name, from the one at `index`.
JNI(void, playUrls)(JNIEnv* env, jclass, jobjectArray urls, jobjectArray names, jint index) {
    std::vector<std::wstring> addresses = ToWideList(env, urls);
    std::vector<std::wstring> shown = ToWideList(env, names);
    g_playlist.clear();
    for (size_t i = 0; i < addresses.size(); i++) {
        if (i < shown.size() && !shown[i].empty()) SetTrackName(addresses[i], shown[i]);
        g_playlist.push_back(addresses[i]);
    }
    StartPlaylistAt(index);
}

// ---------------------------------------------------------------------------
// The playlist
// ---------------------------------------------------------------------------

JNI(jint, trackCount)(JNIEnv*, jclass) { return static_cast<jint>(g_playlist.size()); }
JNI(jint, currentTrack)(JNIEnv*, jclass) { return g_currentTrack; }

JNI(jstring, trackName)(JNIEnv* env, jclass, jint index) {
    if (index < 0 || index >= static_cast<jint>(g_playlist.size())) return ToJava(env, std::wstring());
    return ToJava(env, GetTrackName(g_playlist[static_cast<size_t>(index)]));
}

JNI(jstring, trackPath)(JNIEnv* env, jclass, jint index) {
    if (index < 0 || index >= static_cast<jint>(g_playlist.size())) return ToJava(env, std::wstring());
    return ToJava(env, g_playlist[static_cast<size_t>(index)]);
}

JNI(void, playTrack)(JNIEnv*, jclass, jint index) {
    if (index < 0 || index >= static_cast<jint>(g_playlist.size())) return;
    PlayTrack(index);
    PostStateChange();
}

// ---------------------------------------------------------------------------
// Playback
// ---------------------------------------------------------------------------

JNI(void, playPause)(JNIEnv*, jclass) { PlayPause(); PostStateChange(); }
JNI(void, play)(JNIEnv*, jclass) { Play(); PostStateChange(); }
JNI(void, pause)(JNIEnv*, jclass) { Pause(); PostStateChange(); }
JNI(void, stop)(JNIEnv*, jclass) { Stop(); PostStateChange(); }
JNI(void, nextTrack)(JNIEnv*, jclass) { NextTrack(); PostStateChange(); }
JNI(void, previousTrack)(JNIEnv*, jclass) { PrevTrack(); PostStateChange(); }

JNI(jboolean, loaded)(JNIEnv*, jclass) { return audio::IsLoaded() ? JNI_TRUE : JNI_FALSE; }
JNI(jboolean, playing)(JNIEnv*, jclass) { return IsPlaying() ? JNI_TRUE : JNI_FALSE; }
JNI(jboolean, live)(JNIEnv*, jclass) { return audio::IsLoaded() && audio::IsLive() ? JNI_TRUE : JNI_FALSE; }
JNI(jdouble, position)(JNIEnv*, jclass) { return audio::IsLoaded() ? audio::Position() : 0; }
JNI(jdouble, length)(JNIEnv*, jclass) { return audio::IsLoaded() ? audio::Length() : 0; }

JNI(void, seekTo)(JNIEnv*, jclass, jdouble seconds) {
    SeekToPosition(seconds);
    PostStateChange();
}

JNI(jstring, title)(JNIEnv* env, jclass) {
    if (g_currentTrack < 0 || g_currentTrack >= static_cast<int>(g_playlist.size())) return ToJava(env, std::wstring());
    // The tags' title; a file without one goes by its name
    std::wstring title = GetTagTitle();
    if (title.empty() || title == L"No title" || title == L"Nothing playing") {
        title = GetTrackName(g_playlist[static_cast<size_t>(g_currentTrack)]);
    }
    return ToJava(env, title);
}

JNI(jstring, artist)(JNIEnv* env, jclass) {
    if (!audio::IsLoaded()) return ToJava(env, std::wstring());
    std::wstring artist = GetTagArtist();
    return ToJava(env, artist == L"No artist" ? std::wstring() : artist);
}

JNI(jstring, chapterTitle)(JNIEnv* env, jclass) {
    if (!audio::IsLoaded()) return ToJava(env, std::wstring());
    return ToJava(env, GetCurrentChapterTitle());
}

// "Playing | 128 kbps"
JNI(jstring, statusText)(JNIEnv* env, jclass) {
    if (!audio::IsLoaded()) return ToJava(env, std::wstring());
    std::wstring text;
    switch (audio::GetState()) {
        case audio::State::Playing: text = L"Playing"; break;
        case audio::State::Paused: text = L"Paused"; break;
        case audio::State::Stopped: text = L"Stopped"; break;
        default: break;
    }
    if (int bitrate = GetCurrentBitrate()) {
        if (!text.empty()) text += L" | ";
        text += (IsCurrentVbr() ? L"~" : L"") + std::to_wstring(bitrate) + L" kbps" + (IsCurrentVbr() ? L" VBR" : L"");
    }
    if (g_isRecording) text += L" | REC";
    return ToJava(env, text);
}

JNI(jstring, formatTime)(JNIEnv* env, jclass, jdouble seconds) { return ToJava(env, FormatTime(seconds)); }

// ---------------------------------------------------------------------------
// Seeking, as the desktop's arrows, comma and period do
// ---------------------------------------------------------------------------

JNI(void, seekStep)(JNIEnv*, jclass, jint direction) { SeekStep(direction); }
JNI(void, changeSeekUnit)(JNIEnv*, jclass, jint direction) { ChangeSeekUnit(direction); }

// "5 seconds", "1 track", "1 chapter"; in spring and tape seeking, "8 times".
JNI(jstring, seekUnitText)(JNIEnv* env, jclass) {
    if (IsScrubSeekMode()) {
        int speed = g_seekMode == SEEK_MODE_TAPE ? g_tapeSpeed : g_springSpeed;
        return ToJava(env, std::to_wstring(speed) + L" times");
    }
    if (g_currentSeekIndex == kChapterSeekIndex) return ToJava(env, std::wstring(L"1 chapter"));
    if (g_currentSeekIndex >= 0 && g_currentSeekIndex < g_seekAmountCount) {
        return ToJava(env, std::string(g_seekAmounts[g_currentSeekIndex].label));
    }
    return ToJava(env, std::wstring());
}

JNI(jint, seekMode)(JNIEnv*, jclass) { return g_seekMode; }

JNI(jstring, seekModeName)(JNIEnv* env, jclass) {
    switch (g_seekMode) {
        case SEEK_MODE_SPRING: return ToJava(env, std::wstring(L"Spring"));
        case SEEK_MODE_TAPE: return ToJava(env, std::wstring(L"Tape"));
        default: return ToJava(env, std::wstring(L"Jump"));
    }
}

JNI(void, setSeekMode)(JNIEnv*, jclass, jint mode) {
    if (g_seekMode == mode) return;
    StopScrubbing();
    g_seekMode = std::clamp(static_cast<int>(mode), 0, static_cast<int>(SEEK_MODE_COUNT) - 1);
    PostStateChange();
}

JNI(void, cycleSeekMode)(JNIEnv*, jclass) {
    CycleSeekMode();
    PostStateChange();
}

JNI(jboolean, scrubSeekMode)(JNIEnv*, jclass) { return IsScrubSeekMode() ? JNI_TRUE : JNI_FALSE; }
JNI(void, startScrubbing)(JNIEnv*, jclass, jint direction) { StartScrubbing(direction < 0 ? -1 : 1); }

JNI(void, stopScrubbing)(JNIEnv*, jclass) {
    StopScrubbing();
    PostStateChange();
}

// ---------------------------------------------------------------------------
// Recording
// ---------------------------------------------------------------------------

JNI(void, toggleRecording)(JNIEnv*, jclass) {
    ToggleRecording();
    PostStateChange();
}

JNI(jboolean, recording)(JNIEnv*, jclass) { return g_isRecording ? JNI_TRUE : JNI_FALSE; }

// ---------------------------------------------------------------------------
// The effect controls, as the desktop's brackets and up and down
// ---------------------------------------------------------------------------

JNI(void, cycleParam)(JNIEnv*, jclass, jint direction) {
    CycleParam(direction < 0 ? -1 : 1);
    PostStateChange();
}

JNI(void, adjustParam)(JNIEnv*, jclass, jint direction) {
    AdjustCurrentParam(direction < 0 ? -1 : 1);
    PostStateChange();
}

JNI(void, resetParam)(JNIEnv*, jclass) {
    ResetCurrentParam();
    PostStateChange();
}

JNI(jstring, paramName)(JNIEnv* env, jclass) {
    if (GetAvailableParamCount() == 0) return ToJava(env, std::wstring());
    return ToJava(env, std::string(GetParamName(GetCurrentParam())));
}

// "Tempo 5%"
JNI(jstring, paramText)(JNIEnv* env, jclass) {
    if (GetAvailableParamCount() == 0) return ToJava(env, std::wstring());
    return ToJava(env, DescribeParam(GetCurrentParam()));
}

// identifier, name, text; in threes
JNI(jobjectArray, params)(JNIEnv* env, jclass) {
    std::vector<std::wstring> items;
    for (ParamId id : GetAvailableParamIds()) {
        items.push_back(std::to_wstring(static_cast<int>(id)));
        items.push_back(Utf8ToWide(GetParamName(id)));
        items.push_back(Utf8ToWide(DescribeParam(id)));
    }
    return Flat(env, items);
}

JNI(jint, currentParam)(JNIEnv*, jclass) { return static_cast<jint>(GetCurrentParam()); }

JNI(void, selectParam)(JNIEnv*, jclass, jint identifier) {
    SetCurrentParam(static_cast<ParamId>(identifier));
    PostStateChange();
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

// identifier, name, "1" or "0"; in threes
JNI(jobjectArray, effects)(JNIEnv* env, jclass) {
    static const wchar_t* const streamNames[] = {L"Volume", L"Pitch", L"Tempo", L"Rate"};
    static const wchar_t* const dspNames[] = {L"Reverb", L"Echo", L"Equalizer", L"Compressor", L"Stereo Width",
                                              L"Center Cancel", L"Convolution Reverb", L"3D Audio", L"Normalizer"};
    std::vector<std::wstring> items;
    for (int i = 0; i < static_cast<int>(StreamEffect::COUNT); i++) {
        items.push_back(std::to_wstring(i));
        items.push_back(streamNames[i]);
        items.push_back(IsStreamEffectEnabled(i) ? L"1" : L"0");
    }
    for (int i = 0; i < static_cast<int>(DSPEffectType::COUNT); i++) {
        DSPEffectType type = static_cast<DSPEffectType>(i);
        // Reverb is on when one of its algorithms is chosen
        bool on = type == DSPEffectType::Reverb ? g_reverbAlgorithm != 0 : IsDSPEffectEnabled(type);
        items.push_back(std::to_wstring(kDspEffectBase + i));
        items.push_back(dspNames[i]);
        items.push_back(on ? L"1" : L"0");
    }
    return Flat(env, items);
}

JNI(void, setEffect)(JNIEnv*, jclass, jint identifier, jboolean enabled) {
    const bool on = enabled == JNI_TRUE;
    if (identifier < kDspEffectBase) {
        int index = identifier;
        if (index < 0 || index >= static_cast<int>(StreamEffect::COUNT)) return;
        if (IsStreamEffectEnabled(index) != on) ToggleStreamEffect(index);
    } else {
        int index = identifier - kDspEffectBase;
        if (index < 0 || index >= static_cast<int>(DSPEffectType::COUNT)) return;
        DSPEffectType type = static_cast<DSPEffectType>(index);
        if (type == DSPEffectType::Reverb) {
            if ((g_reverbAlgorithm != 0) != on) SetReverbAlgorithm(on ? 1 : 0);
        } else {
            EnableDSPEffect(type, on);
        }
    }
    SaveSettings();
    PostStateChange();
}

// identifier, name, "1" or "0"; in threes
JNI(jobjectArray, seekUnits)(JNIEnv* env, jclass) {
    std::vector<std::wstring> items;
    for (int i = 0; i < g_seekAmountCount; i++) {
        items.push_back(std::to_wstring(i));
        items.push_back(Utf8ToWide(g_seekAmounts[i].label));
        items.push_back(g_seekEnabled[i] ? L"1" : L"0");
    }
    items.push_back(std::to_wstring(kChapterSeekIndex));
    items.push_back(L"1 chapter");
    items.push_back(g_chapterSeekEnabled ? L"1" : L"0");
    return Flat(env, items);
}

JNI(void, setSeekUnit)(JNIEnv*, jclass, jint identifier, jboolean enabled) {
    if (identifier == kChapterSeekIndex) {
        g_chapterSeekEnabled = enabled == JNI_TRUE;
    } else if (identifier >= 0 && identifier < g_seekAmountCount) {
        g_seekEnabled[identifier] = enabled == JNI_TRUE;
    }
    // The unit in use may just have been turned off: onto one that is on
    if (!IsSeekAmountAvailable(g_currentSeekIndex)) {
        for (int i = 0; i <= kChapterSeekIndex; i++) {
            if (IsSeekAmountAvailable(i)) {
                g_currentSeekIndex = i;
                break;
            }
        }
    }
    SaveSettings();
    PostStateChange();
}

JNI(jstring, engineInfo)(JNIEnv* env, jclass) { return ToJava(env, GetAudioEngineInfo()); }
JNI(jstring, version)(JNIEnv* env, jclass) { return ToJava(env, std::string(APP_VERSION)); }

// ---------------------------------------------------------------------------
// Speech
// ---------------------------------------------------------------------------

JNI(void, speak)(JNIEnv* env, jclass, jstring text) { SpeakW(ToWide(env, text)); }
JNI(jboolean, speechMuted)(JNIEnv*, jclass) { return IsSpeechMuted() ? JNI_TRUE : JNI_FALSE; }
JNI(void, setSpeechMuted)(JNIEnv*, jclass, jboolean muted) { SetSpeechMuted(muted == JNI_TRUE); }

// ---------------------------------------------------------------------------
// app_ui.h: how code outside the UI reaches it
// ---------------------------------------------------------------------------

void RunOnUiThread(std::function<void()> fn) {
    PostToUi(std::move(fn));
}

void PostCommand(int commandId, int param) {
    PostToUi([commandId, param]() { RunCommand(commandId, param); });
}

void ShowMessage(const std::wstring& text, const std::wstring& title, MessageIcon) {
    PostToUi([text, title]() {
        JNIEnv* env = Env();
        static jmethodID message = CallbackMethod("message", "(Ljava/lang/String;Ljava/lang/String;)V");
        jstring jtitle = ToJava(env, title);
        jstring jtext = ToJava(env, text);
        env->CallStaticVoidMethod(Callbacks(), message, jtitle, jtext);
        env->DeleteLocalRef(jtitle);
        env->DeleteLocalRef(jtext);
        CheckException(env, "message");
    });
}

// Nothing in the core asks questions; an app with no modal loop could only say no.
bool AskYesNo(const std::wstring&, const std::wstring&) {
    return false;
}

void* GetMainWindowHandle() {
    return nullptr;
}

void CloseMainWindow() {
    // An Android app is not closed by itself.
}

void UpdateWindowTitle() {
    PostStateChange();
}

void UpdateStatusBar() {
    PostStateChange();
}

void StartScheduleDurationTimer(int ms) {
    PostToUi([ms]() {
        JNIEnv* env = Env();
        static jmethodID start = CallbackMethod("startDurationTimer", "(I)V");
        env->CallStaticVoidMethod(Callbacks(), start, static_cast<jint>(ms));
        CheckException(env, "startDurationTimer");
    });
}

void StopScheduleDurationTimer() {
    PostToUi([]() { CallVoid("stopDurationTimer"); });
}

void NotifyPlaylistTrackChanged() {
    PostStateChange();
}
