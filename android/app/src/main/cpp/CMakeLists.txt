# FastPlay's engine for the Android app: the same C++ as on the desktop and the
# iPhone (the repository's CMakeLists.txt builds it as FastPlayCore for Android),
# and this JNI library, which is the app's way in (Native.kt) and the engine's
# way out (what it asks of the system: paths, speech, HTTP, the UI thread).
#
# FFmpeg for Android is in ffmpeg-android/ at the repository's top: built by CI
# (ci/ffmpeg/build.sh android ffmpeg-android) and fetched by download-deps.
cmake_minimum_required(VERSION 3.22)
project(FastPlayAndroid LANGUAGES C CXX)

set(FASTPLAY_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)
add_subdirectory(${FASTPLAY_ROOT} fastplay-core)

add_library(fastplay SHARED
    android_platform.cpp
    engine_jni.cpp
    settings_jni.cpp
    sources_jni.cpp
    remote_jni.cpp
)
target_compile_features(fastplay PRIVATE cxx_std_17)
target_link_libraries(fastplay PRIVATE FastPlay android log)
# Only the JNI entry points are the library's business
target_link_options(fastplay PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)

# ---- YouTube: QuickJS, the JavaScript runtime yt-dlp answers YouTube's challenges with ----
# QuickJS-NG's qjs, built as a program for each ABI. It goes in the APK's native
# library folder as libqjs.so: the one place an app may run a program from, and
# a name the packaging takes (useLegacyPackaging unpacks it as a file). The NDK's
# 16 KB page flag (ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES) applies to programs too.
include(FetchContent)
FetchContent_Declare(quickjs_ng
    URL https://github.com/quickjs-ng/quickjs/archive/refs/tags/v0.17.0.tar.gz
    URL_HASH SHA256=559bc4c420475e55c7ab4510adbc562f55d7524d75e8e89d79ce4bb02f5687d9
)
FetchContent_GetProperties(quickjs_ng)
if(NOT quickjs_ng_POPULATED)
    FetchContent_Populate(quickjs_ng)
    set(QJS_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
    # Only what qjs needs is built (EXCLUDE_FROM_ALL: its tests and tools are not)
    add_subdirectory(${quickjs_ng_SOURCE_DIR} ${quickjs_ng_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()
set_target_properties(qjs_exe PROPERTIES
    OUTPUT_NAME qjs
    PREFIX lib
    SUFFIX .so
    ENABLE_EXPORTS FALSE
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
)
target_link_options(qjs_exe PRIVATE -Wl,--gc-sections)
add_dependencies(fastplay qjs_exe)
