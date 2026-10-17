# miniaudio 0.11.25 assumes a surround layout when Core Audio cannot supply one.
# Aggregate devices still expose every hardware channel, even when Audio MIDI
# Setup assigns just two of them to stereo. Honor that assignment before the
# converter is built, on both initial open and automatic default-device reroute.
# Keep this in the backend: its reroute notification arrives AFTER audio restarts.
# Windows, iOS, Android, capture and non-stereo clients retain upstream behavior.
#
# Generate a copy, not a patch to FetchContent's checkout. Fail on a changed
# upstream insertion point so a dependency update cannot silently lose the fix.
file(READ "${miniaudio_SOURCE_DIR}/miniaudio.h" miniaudio_header)

set(stereo_helper [=[
#if defined(MA_APPLE_DESKTOP)
static void ma_get_stereo_channel_map__coreaudio(ma_context* pContext, AudioObjectID deviceObjectID, ma_uint32 channels, ma_channel* pChannelMap)
{
    AudioObjectPropertyAddress address;
    UInt32 pair[2] = {1, 2};
    UInt32 size = sizeof(pair);
    OSStatus status;

    /* Mono devices retain miniaudio's stereo-to-mono conversion. */
    if (channels < 2) {
        return;
    }

    address.mSelector = kAudioDevicePropertyPreferredChannelsForStereo;
    address.mScope    = kAudioObjectPropertyScopeOutput;
    address.mElement  = AUDIO_OBJECT_PROPERTY_ELEMENT;
    status = ((ma_AudioObjectGetPropertyData_proc)pContext->coreaudio.AudioObjectGetPropertyData)(deviceObjectID, &address, 0, NULL, &size, pair);

    /* Core Audio numbers channels from 1. Unsupported or invalid preferences
       fall back to the first pair, without spreading stereo across other buses. */
    if (status != noErr || size != sizeof(pair) || pair[0] == 0 || pair[1] == 0 ||
        pair[0] > channels || pair[1] > channels || pair[0] == pair[1]) {
        pair[0] = 1;
        pair[1] = 2;
    }

    ma_channel_map_init_blank(pChannelMap, channels);
    pChannelMap[pair[0] - 1] = MA_CHANNEL_FRONT_LEFT;
    pChannelMap[pair[1] - 1] = MA_CHANNEL_FRONT_RIGHT;
}
#endif

]=])
set(helper_anchor "static ma_result ma_device_init_internal__coreaudio(")

set(stereo_routing [=[
#if defined(MA_APPLE_DESKTOP)
    /* FastPlay: stereo uses the selected device's configured pair, not surround
       upmixing. This also runs before the converter is rebuilt on a reroute. */
    if (deviceType == ma_device_type_playback && pData->channelsIn == 2) {
        ma_get_stereo_channel_map__coreaudio(pContext, deviceObjectID, pData->channelsOut, pData->channelMapOut);
    }
#endif

]=])
set(routing_anchor "    /* Buffer size. Not allowing this to be configurable on iOS. */")

foreach(anchor IN ITEMS helper_anchor routing_anchor)
    string(FIND "${miniaudio_header}" "${${anchor}}" position)
    if(position EQUAL -1)
        message(FATAL_ERROR "miniaudio changed: review the Core Audio stereo-routing fix (${anchor}).")
    endif()
endforeach()
string(REPLACE "${helper_anchor}" "${stereo_helper}${helper_anchor}" miniaudio_header "${miniaudio_header}")
string(REPLACE "${routing_anchor}" "${stereo_routing}${routing_anchor}" miniaudio_header "${miniaudio_header}")
file(MAKE_DIRECTORY "${FASTPLAY_MINIAUDIO_DIR}")
file(CONFIGURE OUTPUT "${FASTPLAY_MINIAUDIO_DIR}/miniaudio.h" CONTENT "${miniaudio_header}" @ONLY)
unset(miniaudio_header)
