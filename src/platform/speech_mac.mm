// macOS speech. Foreground feedback uses accessibility announcements. VoiceOver
// ignores those from background apps, so global hotkey feedback uses its output
// Apple event instead. The system voice is used when VoiceOver is unavailable or
// its scripting permission is denied.

#include "accessibility.h"
#include "app_ui.h"
#include "utils.h"

#import <AppKit/AppKit.h>
#import <AVFoundation/AVFoundation.h>

#include <cstdint>
#include <mutex>
#include <string>

static std::mutex g_speechMutex;
static std::wstring g_pendingSpeechW;
static bool g_speechInterrupt = true;
static bool g_speechInitialized = false;
static std::uint64_t g_speechSequence = 0;
static AVSpeechSynthesizer* g_synthesizer = nil;

static void StopSynthesizer() {
	if (g_synthesizer && g_synthesizer.speaking) {
		[g_synthesizer stopSpeakingAtBoundary:AVSpeechBoundaryImmediate];
	}
}

// UI thread only, including the fallback after a failed VoiceOver request.
static void SpeakSystemVoice(NSString* message, bool interrupt) {
	if (!g_synthesizer) g_synthesizer = [[AVSpeechSynthesizer alloc] init];
	if (interrupt) StopSynthesizer();
	[g_synthesizer speakUtterance:[AVSpeechUtterance speechUtteranceWithString:message]];
}

static bool IsCurrentSpeech(std::uint64_t sequence) {
	std::lock_guard<std::mutex> lock(g_speechMutex);
	return g_speechInitialized && sequence == g_speechSequence;
}

static bool OutputToVoiceOver(pid_t pid, NSString* message, std::uint64_t sequence) {
	NSAppleEventDescriptor* target = [NSAppleEventDescriptor descriptorWithProcessIdentifier:pid];
	// Request Automation access on this worker thread, before starting the send
	// timeout. Recheck the text after the user has answered the permission prompt.
	if (AEDeterminePermissionToAutomateTarget(target.aeDesc, 'VOAS', 'outp', true) != noErr ||
		!IsCurrentSpeech(sequence)) return false;
	// VoiceOver.sdef defines output as VOAS/outp. Send the text as a parameter,
	// rather than constructing AppleScript source from a track title or tag.
	NSAppleEventDescriptor* event = [NSAppleEventDescriptor appleEventWithEventClass:'VOAS'
		eventID:'outp' targetDescriptor:target
		returnID:kAutoGenerateReturnID transactionID:kAnyTransactionID];
	[event setParamDescriptor:[NSAppleEventDescriptor descriptorWithString:message] forKeyword:keyDirectObject];
	NSError* error = nil;
	NSAppleEventDescriptor* reply = [event sendEventWithOptions:NSAppleEventSendWaitForReply | NSAppleEventSendNeverInteract
		timeout:2 error:&error];
	return reply != nil && [reply paramDescriptorForKeyword:keyErrorNumber].int32Value == 0;
}

static void SpeakBackgroundVoiceOver(NSString* message, bool interrupt, std::uint64_t sequence) {
	NSRunningApplication* voiceOver = [NSRunningApplication runningApplicationsWithBundleIdentifier:@"com.apple.VoiceOver"].firstObject;
	if (!voiceOver) {
		SpeakSystemVoice(message, interrupt);
		return;
	}
	pid_t pid = voiceOver.processIdentifier;
	static dispatch_queue_t queue = dispatch_queue_create("com.fastplay.macos.voiceover", DISPATCH_QUEUE_SERIAL);
	// Waiting for VoiceOver (or macOS's Automation prompt) must not block hotkeys
	// or playback. Coalesce queued requests just as SpeakW coalesces UI requests.
	dispatch_async(queue, ^{
		@autoreleasepool {
			if (!IsCurrentSpeech(sequence)) return;
			if (OutputToVoiceOver(pid, message, sequence)) return;
			RunOnUiThread([message, interrupt, sequence] {
				if (IsCurrentSpeech(sequence)) SpeakSystemVoice(message, interrupt);
			});
		}
	});
}

// Speak whatever is pending. Runs on the UI thread.
static void DoSpeak() {
	std::wstring text;
	bool interrupt;
	std::uint64_t sequence;
	{
		std::lock_guard<std::mutex> lock(g_speechMutex);
		if (!g_speechInitialized || g_pendingSpeechW.empty()) return;
		text.swap(g_pendingSpeechW);
		interrupt = g_speechInterrupt;
		sequence = g_speechSequence;
	}

	NSString* message = [NSString stringWithUTF8String:WideToUtf8(text).c_str()];
	if (message.length == 0) return;

	if ([NSWorkspace sharedWorkspace].voiceOverEnabled) {
		StopSynthesizer();
		if (!NSApp.active) {
			SpeakBackgroundVoiceOver(message, interrupt, sequence);
			return;
		}
		NSDictionary* info = @{
			NSAccessibilityAnnouncementKey: message,
			NSAccessibilityPriorityKey: @(interrupt ? NSAccessibilityPriorityHigh : NSAccessibilityPriorityMedium),
		};
		NSAccessibilityPostNotificationWithUserInfo(NSApp, NSAccessibilityAnnouncementRequestedNotification, info);
		return;
	}

	SpeakSystemVoice(message, interrupt);
}

void SpeakW(const wchar_t* text, bool interrupt) {
	{
		std::lock_guard<std::mutex> lock(g_speechMutex);
		if (!g_speechInitialized) return;
		g_pendingSpeechW = text;
		g_speechInterrupt = interrupt;
		++g_speechSequence;
	}
	RunOnUiThread(DoSpeak);
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
	if (g_speechInitialized) {
		g_speechInitialized = false;
		++g_speechSequence;
		// Called on the UI thread as FastPlay closes.
		StopSynthesizer();
		g_synthesizer = nil;
	}
}
