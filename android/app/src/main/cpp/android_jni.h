// What the JNI files share: the Java VM, strings both ways, the app's callbacks
// (Callbacks.kt), and running code on the UI thread.
#pragma once

#include <jni.h>

#include <functional>
#include <string>
#include <vector>

namespace fp {

// This thread's JNIEnv, attaching the thread to the VM first if it is one of the
// engine's own (an attached thread is let go of when it ends).
JNIEnv* Env();

// Java strings are UTF-16 and wchar_t is UTF-32 here: converted properly, so
// names outside the basic plane (emoji in a track's title) survive.
std::wstring ToWide(JNIEnv* env, jstring text);
std::string ToUtf8(JNIEnv* env, jstring text);
jstring ToJava(JNIEnv* env, const std::wstring& text);
jstring ToJava(JNIEnv* env, const std::string& utf8);
jobjectArray ToJavaArray(JNIEnv* env, const std::vector<std::wstring>& items);
std::vector<std::wstring> ToWideList(JNIEnv* env, jobjectArray items);

// me.masonasons.fastplay.engine.Callbacks, held for the life of the process
jclass Callbacks();
// A static method of Callbacks, looked up once
jmethodID CallbackMethod(const char* name, const char* signature);
// Clears (and logs) an exception a callback threw, so it never reaches the engine
void CheckException(JNIEnv* env, const char* where);

// Runs `fn` on the app's main thread, in order with everything posted before it.
void PostToUi(std::function<void()> fn);

}  // namespace fp
