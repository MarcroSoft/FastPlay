/* miniaudio's implementation, compiled once. FastPlay uses only its devices and
   its ring buffer; the features it does not use are left out (MA_NO_* in
   CMakeLists.txt, for every file that includes miniaudio.h). CMake supplies the
   header with our macOS stereo-channel fix; see cmake/miniaudio.cmake. */
#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"
