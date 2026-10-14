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
