/* macOS regression test, after configuring FastPlay with CMake:
   cc -std=c99 -Ibuild/fastplay-miniaudio tests/miniaudio_routing_test.c -o /tmp/miniaudio_routing_test
   /tmp/miniaudio_routing_test
   /tmp/miniaudio_routing_test --devices

   The optional device check opens playback devices but NEVER starts playback.
   It checks the actual converter maps and the backend's default-device reinit.
   No FFmpeg, UI or tempo dependencies are needed for this test. */
#define MA_NO_DECODING
#define MA_NO_ENCODING
#define MA_NO_GENERATION
#define MA_NO_ENGINE
#define MA_NO_RESOURCE_MANAGER
#define MA_NO_NODE_GRAPH
#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"

#include <stdio.h>
#include <stdlib.h>

static void check(int condition, const char* message)
{
    if (!condition) {
        fprintf(stderr, "FAIL: %s\n", message);
        exit(1);
    }
}

/* Distinct left/right samples detect swaps, bleed, attenuation and wrong pairs.
   Equal samples cover mono content already decoded to stereo by FastPlay. */
static void check_conversion(const ma_channel* map, ma_uint32 channels, ma_uint32 left, ma_uint32 right)
{
    const float input[] = {1, 0, 0, 1, 0.25f, -0.5f, 0.75f, 0.75f, 0, 0};
    float output[5 * MA_MAX_CHANNELS];
    ma_channel_converter converter;
    ma_channel_converter_config config = ma_channel_converter_config_init(
        ma_format_f32, 2, NULL, channels, map, ma_channel_mix_mode_default);
    ma_uint32 frame, channel;
    check(ma_channel_converter_init(&config, NULL, &converter) == MA_SUCCESS, "converter init");
    /* Dirty the buffer: unused channels must be explicitly silenced. */
    for (channel = 0; channel < 5 * MA_MAX_CHANNELS; ++channel) output[channel] = 42;
    check(ma_channel_converter_process_pcm_frames(&converter, output, input, 5) == MA_SUCCESS, "conversion");
    for (frame = 0; frame < 5; ++frame) {
        for (channel = 0; channel < channels; ++channel) {
            float expected = channel == left ? input[frame * 2] : channel == right ? input[frame * 2 + 1] : 0;
            check(output[frame * channels + channel] == expected, "signal escaped its stereo pair or changed");
        }
    }
    ma_channel_converter_uninit(&converter, NULL);
}

#if defined(MA_APPLE_DESKTOP) && defined(MA_SUPPORT_COREAUDIO)
static UInt32 preferred[2];
static UInt32 returnedSize;
static OSStatus returnedStatus;
static unsigned queries;

static OSStatus fake_property(AudioObjectID object, const AudioObjectPropertyAddress* address,
    UInt32 qualifierSize, const void* qualifier, UInt32* size, void* data)
{
    check(object == 123, "queried the wrong device");
    check(address->mSelector == kAudioDevicePropertyPreferredChannelsForStereo, "wrong property");
    check(address->mScope == kAudioObjectPropertyScopeOutput, "wrong scope");
    check(address->mElement == AUDIO_OBJECT_PROPERTY_ELEMENT, "wrong element");
    check(qualifierSize == 0 && qualifier == NULL && *size == sizeof(preferred), "wrong query size");
    ++queries;
    memcpy(data, preferred, sizeof(preferred));
    *size = returnedSize;
    return returnedStatus;
}

static void test_pair(ma_uint32 channels, UInt32 left, UInt32 right, OSStatus status, UInt32 size,
    ma_uint32 expectedLeft, ma_uint32 expectedRight)
{
    ma_context context;
    ma_channel map[MA_MAX_CHANNELS + 1];
    ma_uint32 i;
    memset(&context, 0, sizeof(context));
    context.coreaudio.AudioObjectGetPropertyData = (ma_proc)fake_property;
    preferred[0] = left;
    preferred[1] = right;
    returnedStatus = status;
    returnedSize = size;
    queries = 0;
    memset(map, 0x7f, sizeof(map));
    ma_get_stereo_channel_map__coreaudio(&context, 123, channels, map);
    check(queries == 1, "stereo preference was not read");
    for (i = 0; i < channels; ++i) {
        ma_channel expected = i == expectedLeft ? MA_CHANNEL_FRONT_LEFT : i == expectedRight ? MA_CHANNEL_FRONT_RIGHT : MA_CHANNEL_NONE;
        check(map[i] == expected, "incorrect channel label");
    }
    check(map[channels] == 0x7f, "channel map overflow");
    check_conversion(map, channels, expectedLeft, expectedRight);
}

static void test_preferences(void)
{
    ma_context context;
    ma_channel mono = MA_CHANNEL_MONO;
    test_pair(10, 1, 2, noErr, 8, 0, 1);   /* System GoXLR */
    test_pair(10, 3, 4, noErr, 8, 2, 3);   /* Game GoXLR */
    test_pair(10, 10, 9, noErr, 8, 9, 8);  /* Last pair, reversed */
    test_pair(10, 2, 9, noErr, 8, 1, 8);   /* Nonadjacent channels */
    test_pair(2, 1, 2, noErr, 8, 0, 1);    /* Ordinary stereo */
    test_pair(2, 2, 1, noErr, 8, 1, 0);
    test_pair(MA_MAX_CHANNELS, MA_MAX_CHANNELS, 1, noErr, 8, MA_MAX_CHANNELS - 1, 0);
    test_pair(10, 3, 4, -1, 8, 0, 1);      /* Unavailable property */
    test_pair(10, 3, 4, noErr, 4, 0, 1);   /* Incomplete property */
    test_pair(10, 0, 4, noErr, 8, 0, 1);
    test_pair(10, 3, 0, noErr, 8, 0, 1);
    test_pair(10, 11, 4, noErr, 8, 0, 1);
    test_pair(10, 3, 11, noErr, 8, 0, 1);
    test_pair(10, 3, 3, noErr, 8, 0, 1);
    memset(&context, 0, sizeof(context));
    queries = 0;
    ma_get_stereo_channel_map__coreaudio(&context, 123, 1, &mono);
    check(mono == MA_CHANNEL_MONO && queries == 0, "mono device changed");
}

static void check_device(ma_device* device)
{
    ma_channel expected[MA_MAX_CHANNELS] = {0};
    char name[256] = {0};
    ma_uint32 left = 0, right = 0, i;
    ma_uint32 channels = device->playback.internalChannels;
    check(device->playback.channels == 2, "FastPlay callback is no longer stereo");
    if (channels < 2) return;
    ma_get_stereo_channel_map__coreaudio(device->pContext, device->coreaudio.deviceObjectIDPlayback, channels, expected);
    for (i = 0; i < channels; ++i) {
        check(expected[i] == device->playback.internalChannelMap[i], "backend did not install preferred pair");
        if (expected[i] == MA_CHANNEL_FRONT_LEFT) left = i;
        if (expected[i] == MA_CHANNEL_FRONT_RIGHT) right = i;
    }
    check_conversion(device->playback.internalChannelMap, channels, left, right);
    /* miniaudio retains the original display name after a default reroute. */
    check(ma_get_AudioObject_name(device->pContext, device->coreaudio.deviceObjectIDPlayback, sizeof(name), name) == MA_SUCCESS, "device name");
    printf("  %s: %u channels, stereo on %u/%u PASS\n", name, channels, left + 1, right + 1);
}

static void test_devices(void)
{
    ma_backend backend = ma_backend_coreaudio;
    ma_context context;
    ma_device_info* devices;
    ma_uint32 count, i;
    check(ma_context_init(&backend, 1, NULL, &context) == MA_SUCCESS, "Core Audio context");
    check(ma_context_get_devices(&context, &devices, &count, NULL, NULL) == MA_SUCCESS, "device list");
    for (i = 0; i <= count; ++i) {
        ma_device device;
        ma_device_config config = ma_device_config_init(ma_device_type_playback);
        config.playback.pDeviceID = i < count ? &devices[i].id : NULL;
        config.playback.format = ma_format_f32;
        config.playback.channels = 2;
        check(ma_device_init(&context, &config, &device) == MA_SUCCESS, "device open");
        check_device(&device);
        /* Exercise the same reopen + converter rebuild as an automatic default
           device change, while stopped, without changing the system default. */
        printf("  Reopening on the system default:\n");
        check(ma_device_reinit_internal__coreaudio(&device, ma_device_type_playback, MA_TRUE) == MA_SUCCESS, "default reroute");
        check(ma_device__post_init_setup(&device, ma_device_type_playback) == MA_SUCCESS, "reroute converter");
        check_device(&device);
        ma_device_uninit(&device);
    }
    ma_context_uninit(&context);
}
#endif

int main(int argc, char** argv)
{
    check_conversion(NULL, 2, 0, 1);
#if defined(MA_APPLE_DESKTOP) && defined(MA_SUPPORT_COREAUDIO)
    test_preferences();
    if (argc == 2 && strcmp(argv[1], "--devices") == 0) test_devices();
#else
    (void)argc;
    (void)argv;
#endif
    puts("Stereo routing tests passed.");
    return 0;
}
