# Codec 2's speech codec as a static library, for FFmpeg's libcodec2 decoder. Built
# by ci/ffmpeg/build.sh into the same prefix as FFmpeg, so its libraries go out with
# FFmpeg's. Only the codec: Codec 2's own build also makes the FreeDV modems (which
# use C99 complex numbers, beyond MSVC) and its programs.
#
#   CODEC2_SRC        Codec 2's source tree
#   CODEC2_TAG        its version (ci/ffmpeg/CODEC2_VERSION), for codec2/version.h
#   CODEC2_CODEBOOKS  the codebook*.c files its generate_codebook made (build.sh makes
#                     them with the host's compiler, so a cross build needs no host tool)
cmake_minimum_required(VERSION 3.15)
# A library only, so CMake's compiler checks need not link a program (for iOS)
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
project(CODEC2 VERSION ${CODEC2_TAG} LANGUAGES C)

# The static runtime (/MT), as FFmpeg and FastPlay
set(CMAKE_MSVC_RUNTIME_LIBRARY MultiThreaded)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

file(TO_CMAKE_PATH "${CODEC2_SRC}" CODEC2_SRC)
file(TO_CMAKE_PATH "${CODEC2_CODEBOOKS}" CODEC2_CODEBOOKS)
set(S ${CODEC2_SRC}/src)
add_library(codec2 STATIC
    ${S}/codec2.c ${S}/codec2_fft.c ${S}/kiss_fft.c ${S}/kiss_fftr.c
    ${S}/dump.c ${S}/interp.c ${S}/lpc.c ${S}/lsp.c ${S}/mbest.c ${S}/newamp1.c
    ${S}/nlp.c ${S}/pack.c ${S}/phase.c ${S}/postfilter.c ${S}/quantise.c ${S}/sine.c
    ${CODEC2_CODEBOOKS}/codebook.c ${CODEC2_CODEBOOKS}/codebookd.c
    ${CODEC2_CODEBOOKS}/codebookjmv.c ${CODEC2_CODEBOOKS}/codebookge.c
    ${CODEC2_CODEBOOKS}/codebooknewamp1.c ${CODEC2_CODEBOOKS}/codebooknewamp1_energy.c
    ${CODEC2_CODEBOOKS}/codebooknewamp2.c ${CODEC2_CODEBOOKS}/codebooknewamp2_energy.c)
configure_file(${CODEC2_SRC}/cmake/version.h.in codec2/version.h)
target_include_directories(codec2 PRIVATE ${S} ${CMAKE_CURRENT_BINARY_DIR})
# On Windows build.sh compiles it with clang-cl, for its variable-length arrays
if(MSVC)
    target_compile_definitions(codec2 PRIVATE _USE_MATH_DEFINES _CRT_SECURE_NO_WARNINGS)
endif()

install(TARGETS codec2 ARCHIVE DESTINATION lib)
install(FILES ${S}/codec2.h ${CMAKE_CURRENT_BINARY_DIR}/codec2/version.h DESTINATION include/codec2)
