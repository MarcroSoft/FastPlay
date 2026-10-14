// Files on a server FFmpeg can reach by an address (FTP): listing a folder and
// copying a file down, the iPhone's FPRemote.mm. Both block until done, so the
// app calls them off the main thread.

#include "android_jni.h"
#include "utils.h"

extern "C" {
#include <libavformat/avio.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
}

#include <cerrno>
#include <cstdio>
#include <string>
#include <vector>

using namespace fp;

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_me_masonasons_fastplay_engine_Native_##name

namespace {

// How long a server may say nothing before it is given up on, in microseconds
const char* kTimeout = "20000000";

std::string ErrorFor(int code, const char* what) {
    char text[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(code, text, sizeof(text));
    return std::string(what) + " " + text + ".";
}

}  // namespace

// What is in the folder at `url` (which ends with a slash): name, "1" for a
// folder or "0", size in bytes, modified (seconds since 1970, 0 if not known);
// in fours. On failure, one string: the error.
JNI(jobjectArray, ftpList)(JNIEnv* env, jclass, jstring url) {
    const std::string address = ToUtf8(env, url);
    AVDictionary* options = nullptr;
    av_dict_set(&options, "timeout", kTimeout, 0);
    AVIODirContext* dir = nullptr;
    int rc = avio_open_dir(&dir, address.c_str(), &options);
    av_dict_free(&options);
    if (rc < 0) return ToJavaArray(env, {Utf8ToWide(ErrorFor(rc, "Could not open the folder:"))});
    std::vector<std::wstring> items;
    for (;;) {
        AVIODirEntry* entry = nullptr;
        rc = avio_read_dir(dir, &entry);
        if (rc < 0 || !entry) break;
        std::string name = entry->name ? entry->name : "";
        if (!name.empty() && name != "." && name != "..") {
            const long long modified = entry->modification_timestamp > 0 ? entry->modification_timestamp / 1000000 : 0;
            items.push_back(Utf8ToWide(name));
            items.push_back(entry->type == AVIO_ENTRY_DIRECTORY ? L"1" : L"0");
            items.push_back(std::to_wstring(entry->size > 0 ? entry->size : 0));
            items.push_back(std::to_wstring(modified));
        }
        avio_free_directory_entry(&entry);
    }
    avio_close_dir(&dir);
    if (rc == AVERROR_EOF) rc = 0;  // how FTP says the listing has ended
    if (rc < 0) return ToJavaArray(env, {Utf8ToWide(ErrorFor(rc, "Could not read the folder:"))});
    return ToJavaArray(env, items);
}

// Copies the file at `url` to `path`. `cancel` (a Cancellation.kt) is asked now and
// then; true gives up, leaving nothing behind. "" when done, or the error.
JNI(jstring, ftpCopy)(JNIEnv* env, jclass, jstring url, jstring path, jobject cancel) {
    const std::string address = ToUtf8(env, url);
    const std::string target = ToUtf8(env, path);
    jmethodID cancelled = nullptr;
    if (cancel) {
        jclass type = env->GetObjectClass(cancel);
        cancelled = env->GetMethodID(type, "isCancelled", "()Z");
        env->DeleteLocalRef(type);
    }
    AVDictionary* options = nullptr;
    av_dict_set(&options, "timeout", kTimeout, 0);
    AVIOContext* source = nullptr;
    int rc = avio_open2(&source, address.c_str(), AVIO_FLAG_READ, nullptr, &options);
    av_dict_free(&options);
    if (rc < 0) return ToJava(env, ErrorFor(rc, "Could not open the file:"));
    FILE* file = fopen(target.c_str(), "wb");
    if (!file) {
        avio_closep(&source);
        return ToJava(env, ErrorFor(AVERROR(errno), "Could not create the file here:"));
    }
    std::vector<unsigned char> chunk(256 * 1024);
    std::string error;
    for (;;) {
        if (cancelled && env->CallBooleanMethod(cancel, cancelled)) {
            error = "Cancelled.";
            break;
        }
        const int n = avio_read(source, chunk.data(), static_cast<int>(chunk.size()));
        if (n == AVERROR_EOF || n == 0) break;
        if (n < 0) {
            error = ErrorFor(n, "The download stopped:");
            break;
        }
        if (fwrite(chunk.data(), 1, static_cast<size_t>(n), file) != static_cast<size_t>(n)) {
            error = ErrorFor(AVERROR(errno), "Could not write the file here:");
            break;
        }
    }
    fclose(file);
    avio_closep(&source);
    if (!error.empty()) remove(target.c_str());
    return ToJava(env, error);
}
