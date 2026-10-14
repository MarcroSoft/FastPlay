// What the engine asks of Android: where things are kept, speech, HTTP and the UI
// thread. The iPhone's are src/platform/*_ios.mm; these call into the app's
// Kotlin (Callbacks.kt), which has what C++ cannot reach on Android.

#include "android_jni.h"

#include "accessibility.h"
#include "http.h"
#include "paths.h"
#include "platform.h"
#include "utils.h"

#include <android/log.h>
#include <pthread.h>
#include <sys/system_properties.h>

#include <deque>
#include <mutex>
#include <string>

namespace {

JavaVM* g_vm = nullptr;
jclass g_callbacks = nullptr;
pthread_key_t g_detachKey;

void DetachThread(void*) {
    if (g_vm) g_vm->DetachCurrentThread();
}

// The folders, given by the app as it starts (Native.init)
std::mutex g_pathMutex;
std::wstring g_dataDir, g_documentsDir, g_tempDir, g_libraryDir;

std::wstring WithSlash(std::wstring dir) {
    if (dir.empty() || dir.back() != L'/') dir += L'/';
    return dir;
}

// The UI thread's queue: filled from any thread, emptied by Native.runUiQueue
std::mutex g_uiMutex;
std::deque<std::function<void()>> g_uiQueue;

}  // namespace

namespace fp {

JNIEnv* Env() {
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    // Let go of the thread as it ends, or the VM would wait for it at exit
    pthread_setspecific(g_detachKey, env);
    return env;
}

std::wstring ToWide(JNIEnv* env, jstring text) {
    if (!text) return {};
    const jsize length = env->GetStringLength(text);
    const jchar* chars = env->GetStringChars(text, nullptr);
    std::wstring out;
    out.reserve(static_cast<size_t>(length));
    for (jsize i = 0; i < length; i++) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < length && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            i++;
        }
        out.push_back(static_cast<wchar_t>(c));
    }
    env->ReleaseStringChars(text, chars);
    return out;
}

std::string ToUtf8(JNIEnv* env, jstring text) {
    return WideToUtf8(ToWide(env, text));
}

jstring ToJava(JNIEnv* env, const std::wstring& text) {
    std::u16string units;
    units.reserve(text.size());
    for (wchar_t w : text) {
        uint32_t c = static_cast<uint32_t>(w);
        if (c >= 0x10000 && c <= 0x10FFFF) {
            c -= 0x10000;
            units.push_back(static_cast<char16_t>(0xD800 + (c >> 10)));
            units.push_back(static_cast<char16_t>(0xDC00 + (c & 0x3FF)));
        } else if (c < 0x10000) {
            units.push_back(static_cast<char16_t>(c));
        } else {
            units.push_back(u'�');
        }
    }
    return env->NewString(reinterpret_cast<const jchar*>(units.data()), static_cast<jsize>(units.size()));
}

jstring ToJava(JNIEnv* env, const std::string& utf8) {
    return ToJava(env, Utf8ToWide(utf8));
}

jobjectArray ToJavaArray(JNIEnv* env, const std::vector<std::wstring>& items) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(items.size()), stringClass, nullptr);
    for (size_t i = 0; i < items.size(); i++) {
        jstring item = ToJava(env, items[i]);
        env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    env->DeleteLocalRef(stringClass);
    return array;
}

std::vector<std::wstring> ToWideList(JNIEnv* env, jobjectArray items) {
    std::vector<std::wstring> out;
    if (!items) return out;
    const jsize count = env->GetArrayLength(items);
    for (jsize i = 0; i < count; i++) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(items, i));
        out.push_back(ToWide(env, item));
        env->DeleteLocalRef(item);
    }
    return out;
}

jclass Callbacks() {
    return g_callbacks;
}

jmethodID CallbackMethod(const char* name, const char* signature) {
    return Env()->GetStaticMethodID(g_callbacks, name, signature);
}

void CheckException(JNIEnv* env, const char* where) {
    if (!env->ExceptionCheck()) return;
    __android_log_print(ANDROID_LOG_ERROR, "FastPlay", "Exception in %s", where);
    env->ExceptionDescribe();
    env->ExceptionClear();
}

void PostToUi(std::function<void()> fn) {
    bool wasEmpty;
    {
        std::lock_guard<std::mutex> lock(g_uiMutex);
        wasEmpty = g_uiQueue.empty();
        g_uiQueue.push_back(std::move(fn));
    }
    // One wake for however much is queued before the UI thread gets to it
    if (!wasEmpty) return;
    JNIEnv* env = Env();
    if (!env) return;
    static jmethodID postUi = CallbackMethod("postUi", "()V");
    env->CallStaticVoidMethod(g_callbacks, postUi);
    CheckException(env, "postUi");
}

}  // namespace fp

using namespace fp;

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    pthread_key_create(&g_detachKey, DetachThread);
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = env->FindClass("me/masonasons/fastplay/engine/Callbacks");
    if (!local) return JNI_ERR;
    g_callbacks = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL Java_me_masonasons_fastplay_engine_Native_setPaths(
    JNIEnv* env, jclass, jstring data, jstring documents, jstring temp, jstring libraries) {
    std::lock_guard<std::mutex> lock(g_pathMutex);
    g_dataDir = WithSlash(ToWide(env, data));
    g_documentsDir = ToWide(env, documents);
    g_tempDir = WithSlash(ToWide(env, temp));
    g_libraryDir = WithSlash(ToWide(env, libraries));
}

extern "C" JNIEXPORT void JNICALL Java_me_masonasons_fastplay_engine_Native_runUiQueue(JNIEnv*, jclass) {
    for (;;) {
        std::function<void()> fn;
        {
            std::lock_guard<std::mutex> lock(g_uiMutex);
            if (g_uiQueue.empty()) return;
            fn = std::move(g_uiQueue.front());
            g_uiQueue.pop_front();
        }
        fn();
    }
}

// ---------------------------------------------------------------------------
// paths.h
// ---------------------------------------------------------------------------

const wchar_t kPathSeparator = L'/';

std::wstring GetExecutableDir() {
    std::lock_guard<std::mutex> lock(g_pathMutex);
    return g_libraryDir;
}

bool IsInstalledMode() {
    return true;
}

std::wstring GetDataDirectory() {
    std::lock_guard<std::mutex> lock(g_pathMutex);
    return g_dataDir;
}

// FastPlay's own folder of the device's files, as the iPhone's Documents folder:
// recordings and what is copied from servers go there
std::wstring GetUserMusicDir() {
    std::lock_guard<std::mutex> lock(g_pathMutex);
    return g_documentsDir;
}

std::wstring GetUserDownloadsDir() {
    return GetUserMusicDir();
}

std::wstring GetTempDir() {
    std::lock_guard<std::mutex> lock(g_pathMutex);
    return g_tempDir;
}

std::wstring GetLibraryDir() {
    std::lock_guard<std::mutex> lock(g_pathMutex);
    return g_libraryDir;
}

// ---------------------------------------------------------------------------
// platform.h
// ---------------------------------------------------------------------------

void PlatformStartup() {
    // Nothing to load: everything is linked into libfastplay.so.
}

std::string GetSystemDescription() {
    // "Android 14; arm64"
    char release[PROP_VALUE_MAX] = {};
    __system_property_get("ro.build.version.release", release);
    std::string name = std::string("Android ") + (release[0] ? release : "?");
#if defined(__aarch64__)
    return name + "; arm64";
#elif defined(__x86_64__)
    return name + "; x86_64";
#elif defined(__arm__)
    return name + "; arm";
#else
    return name + "; x86";
#endif
}

// ---------------------------------------------------------------------------
// accessibility.h: through TalkBack, as an announcement (Callbacks.speak)
// ---------------------------------------------------------------------------

namespace {
std::mutex g_speechMutex;
std::wstring g_pendingSpeech;
bool g_speechInterrupt = true;
bool g_speechInitialized = false;
bool g_speechMuted = false;

// Speak whatever is pending. Runs on the UI thread.
void DoSpeak() {
    std::wstring text;
    bool interrupt;
    {
        std::lock_guard<std::mutex> lock(g_speechMutex);
        if (!g_speechInitialized || g_pendingSpeech.empty()) return;
        text.swap(g_pendingSpeech);
        interrupt = g_speechInterrupt;
    }
    JNIEnv* env = Env();
    static jmethodID speak = CallbackMethod("speak", "(Ljava/lang/String;Z)V");
    jstring message = ToJava(env, text);
    env->CallStaticVoidMethod(Callbacks(), speak, message, static_cast<jboolean>(interrupt));
    env->DeleteLocalRef(message);
    CheckException(env, "speak");
}
}  // namespace

// The app mutes speech around a change that TalkBack reads out by itself (a
// control's new value), so it is not said twice.
void SetSpeechMuted(bool muted) {
    std::lock_guard<std::mutex> lock(g_speechMutex);
    g_speechMuted = muted;
}

bool IsSpeechMuted() {
    std::lock_guard<std::mutex> lock(g_speechMutex);
    return g_speechMuted;
}

void SpeakW(const wchar_t* text, bool interrupt) {
    {
        std::lock_guard<std::mutex> lock(g_speechMutex);
        if (!g_speechInitialized || g_speechMuted) return;
        g_pendingSpeech = text;
        g_speechInterrupt = interrupt;
    }
    PostToUi(DoSpeak);
}

void SpeakW(const std::wstring& text, bool interrupt) {
    SpeakW(text.c_str(), interrupt);
}

void Speak(const char* text, bool interrupt) {
    SpeakW(Utf8ToWide(text), interrupt);
}

void Speak(const std::string& text, bool interrupt) {
    Speak(text.c_str(), interrupt);
}

bool InitSpeech() {
    std::lock_guard<std::mutex> lock(g_speechMutex);
    g_speechInitialized = true;
    return true;
}

void FreeSpeech() {
    std::lock_guard<std::mutex> lock(g_speechMutex);
    g_speechInitialized = false;
}

// ---------------------------------------------------------------------------
// http.h: through the app's HttpURLConnection (Callbacks.httpGet), which has
// Android's own TLS and certificates
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL Java_me_masonasons_fastplay_engine_Native_httpProgress(
    JNIEnv*, jclass, jlong handle, jlong received, jlong total) {
    auto* progress = reinterpret_cast<const std::function<bool(uint64_t, uint64_t)>*>(handle);
    if (!progress || !*progress) return JNI_TRUE;
    return (*progress)(static_cast<uint64_t>(received), static_cast<uint64_t>(total)) ? JNI_TRUE : JNI_FALSE;
}

HttpResult HttpGet(const std::wstring& url, const HttpOptions& options) {
    HttpResult result;
    JNIEnv* env = Env();
    if (!env) {
        result.errorText = L"No Java thread for the request.";
        return result;
    }
    env->PushLocalFrame(32);
    static jmethodID get = CallbackMethod(
        "httpGet",
        "(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZLjava/lang/String;IJ)"
        "Lme/masonasons/fastplay/engine/HttpResponse;");
    // The user agent first, so a header the caller gives takes its place; the
    // credentials of a protected feed up front, as Basic authentication
    std::vector<std::wstring> headers{L"User-Agent: " + Utf8ToWide(UserAgent())};
    headers.insert(headers.end(), options.headers.begin(), options.headers.end());
    if (!options.username.empty() || !options.password.empty()) {
        headers.push_back(BuildBasicAuthHeader(options.username, options.password));
    }
    jobject response = env->CallStaticObjectMethod(
        Callbacks(), get, ToJava(env, url), ToJavaArray(env, headers), ToJava(env, options.username),
        ToJava(env, options.password), static_cast<jboolean>(options.followRedirects),
        static_cast<jboolean>(options.readBody), ToJava(env, options.saveTo),
        static_cast<jint>(options.timeoutMs),
        options.progress ? reinterpret_cast<jlong>(&options.progress) : static_cast<jlong>(0));
    CheckException(env, "httpGet");
    if (response) {
        jclass type = env->GetObjectClass(response);
        auto field = [&](const char* name, const char* signature) { return env->GetFieldID(type, name, signature); };
        result.completed = env->GetBooleanField(response, field("completed", "Z"));
        result.cancelled = env->GetBooleanField(response, field("cancelled", "Z"));
        result.status = static_cast<unsigned long>(env->GetIntField(response, field("status", "I")));
        result.bytesReceived = static_cast<uint64_t>(env->GetLongField(response, field("bytesReceived", "J")));
        result.systemError = static_cast<unsigned long>(env->GetIntField(response, field("systemError", "I")));
        result.finalUrl = ToWide(env, static_cast<jstring>(env->GetObjectField(response, field("finalUrl", "Ljava/lang/String;"))));
        result.errorText = ToWide(env, static_cast<jstring>(env->GetObjectField(response, field("errorText", "Ljava/lang/String;"))));
        auto body = static_cast<jbyteArray>(env->GetObjectField(response, field("body", "[B")));
        if (body) {
            const jsize size = env->GetArrayLength(body);
            result.body.resize(static_cast<size_t>(size));
            if (size > 0) env->GetByteArrayRegion(body, 0, size, reinterpret_cast<jbyte*>(&result.body[0]));
        }
    } else if (result.errorText.empty()) {
        result.errorText = L"The request could not be made.";
    }
    env->PopLocalFrame(nullptr);
    return result;
}
