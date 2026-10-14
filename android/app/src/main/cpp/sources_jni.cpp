// Internet radio and podcasts for the Android app: the iPhone's FPSources.mm. The
// saved stations and subscriptions (the same database as on the desktop) and the
// directory searches. The searches block while they go over the network: the app
// calls them off the main thread (Sources.kt).
//
// Lists go to the app as flat string arrays, a fixed number of strings an item.

#include "android_jni.h"

#include "database.h"
#include "globals.h"
#include "player.h"
#include "playlist_io.h"
#include "podcast.h"
#include "radio.h"
#include "utils.h"

#include <algorithm>
#include <string>
#include <vector>

using namespace fp;

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_me_masonasons_fastplay_engine_Native_##name

namespace {

// A station found, as RadioResult.kt holds it: name, url, stationId, country,
// codec, bitrate, source, and the detail line ("Germany, MP3, 128 kbps")
const size_t kRadioResultFields = 8;

void AddRadioResult(std::vector<std::wstring>& items, const RadioSearchResult& result) {
    std::wstring detail;
    auto add = [&detail](const std::wstring& part) {
        if (part.empty()) return;
        if (!detail.empty()) detail += L", ";
        detail += part;
    };
    add(result.country);
    add(result.codec);
    if (result.bitrate > 0) add(std::to_wstring(result.bitrate) + L" kbps");
    items.insert(items.end(), {result.name, result.url, result.stationId, result.country, result.codec,
                               std::to_wstring(result.bitrate), std::to_wstring(result.source), detail});
}

RadioSearchResult RadioResultFrom(const std::vector<std::wstring>& fields) {
    RadioSearchResult result;
    if (fields.size() < kRadioResultFields) return result;
    result.name = fields[0];
    result.url = fields[1];
    result.stationId = fields[2];
    result.country = fields[3];
    result.codec = fields[4];
    result.bitrate = static_cast<int>(wcstol(fields[5].c_str(), nullptr, 10));
    result.source = static_cast<int>(wcstol(fields[6].c_str(), nullptr, 10));
    return result;
}

}  // namespace

// ---- Radio ------------------------------------------------------------------

// identifier, name, url; in threes
JNI(jobjectArray, radioFavorites)(JNIEnv* env, jclass) {
    std::vector<std::wstring> items;
    for (const RadioStation& station : GetRadioFavorites()) {
        items.insert(items.end(), {std::to_wstring(station.id), station.name, station.url});
    }
    return ToJavaArray(env, items);
}

// False if the address is already saved.
JNI(jboolean, addRadioFavorite)(JNIEnv* env, jclass, jstring name, jstring url) {
    std::wstring address = ToWide(env, url);
    for (const RadioStation& station : GetRadioFavorites()) {
        if (station.url == address) return JNI_FALSE;
    }
    return AddRadioStation(ToWide(env, name), address) >= 0 ? JNI_TRUE : JNI_FALSE;
}

JNI(void, removeRadioFavorite)(JNIEnv*, jclass, jint identifier) {
    RemoveRadioStation(identifier);
}

// 0 RadioBrowser, 1 TuneIn, 2 iHeartRadio. Blocks.
JNI(jobjectArray, searchRadio)(JNIEnv* env, jclass, jstring query, jint directory) {
    std::wstring text = ToWide(env, query);
    std::vector<RadioSearchResult> results;
    switch (directory) {
        case 1: SearchTuneIn(text, results); break;
        case 2: SearchIHeartRadio(text, results); break;
        default: SearchRadioBrowser(text, L"", results); break;
    }
    std::vector<std::wstring> items;
    for (const RadioSearchResult& result : results) AddRadioResult(items, result);
    return ToJavaArray(env, items);
}

// The address a found station plays from, or "" if it could not be found. Blocks.
JNI(jstring, resolveRadioResult)(JNIEnv* env, jclass, jobjectArray fields) {
    return ToJava(env, ResolveRadioStreamUrl(RadioResultFrom(ToWideList(env, fields))));
}

// The stations of an M3U, M3U8 or PLS playlist file, added to the saved ones
// (those already saved are passed over): { imported, skipped }.
JNI(jintArray, importRadioFavorites)(JNIEnv* env, jclass, jstring path) {
    RadioImportResult result = ImportRadioFavorites(ToWide(env, path));
    jint counts[2] = {static_cast<jint>(result.imported), static_cast<jint>(result.skipped)};
    jintArray out = env->NewIntArray(2);
    env->SetIntArrayRegion(out, 0, 2, counts);
    return out;
}

// The saved stations, written as an M3U file. False if it could not be written.
JNI(jboolean, exportRadioFavorites)(JNIEnv* env, jclass, jstring path) {
    return ExportRadioFavorites(ToWide(env, path), GetRadioFavorites()) ? JNI_TRUE : JNI_FALSE;
}

// ---- Podcasts ---------------------------------------------------------------

// identifier, name, feed address; in threes
JNI(jobjectArray, podcasts)(JNIEnv* env, jclass) {
    std::vector<std::wstring> items;
    for (const PodcastSubscription& subscription : GetPodcastSubscriptions()) {
        items.insert(items.end(), {std::to_wstring(subscription.id), subscription.name, subscription.feedUrl});
    }
    return ToJavaArray(env, items);
}

// The feeds of an OPML file, subscribed to (those already subscribed to are passed
// over): { added, skipped }, added being -1 if the file has no feeds in it.
JNI(jintArray, importPodcasts)(JNIEnv* env, jclass, jstring path) {
    std::vector<OpmlFeed> feeds = ParseOpmlFile(ToWide(env, path));
    jint added = 0, skipped = 0;
    if (feeds.empty()) {
        added = -1;
    } else {
        std::vector<PodcastSubscription> existing = GetPodcastSubscriptions();
        for (const OpmlFeed& feed : feeds) {
            bool have = false;
            for (const PodcastSubscription& subscription : existing) {
                if (subscription.feedUrl == feed.feedUrl) have = true;
            }
            if (have || feed.feedUrl.empty()) {
                skipped++;
                continue;
            }
            if (AddPodcastSubscription(feed.title.empty() ? feed.feedUrl : feed.title, feed.feedUrl) >= 0) {
                added++;
                PodcastSubscription now;
                now.feedUrl = feed.feedUrl;
                existing.push_back(now);  // a feed listed twice in the file is added once
            }
        }
    }
    jint counts[2] = {added, skipped};
    jintArray out = env->NewIntArray(2);
    env->SetIntArrayRegion(out, 0, 2, counts);
    return out;
}

// The subscriptions, written as an OPML file. False if it could not be written.
JNI(jboolean, exportPodcasts)(JNIEnv* env, jclass, jstring path) {
    return ExportOpmlFile(ToWide(env, path), GetPodcastSubscriptions()) ? JNI_TRUE : JNI_FALSE;
}

JNI(jboolean, isSubscribedToFeed)(JNIEnv* env, jclass, jstring feedUrl) {
    std::wstring feed = ToWide(env, feedUrl);
    for (const PodcastSubscription& subscription : GetPodcastSubscriptions()) {
        if (subscription.feedUrl == feed) return JNI_TRUE;
    }
    return JNI_FALSE;
}

JNI(void, subscribeToPodcast)(JNIEnv* env, jclass, jstring name, jstring feedUrl) {
    std::wstring feed = ToWide(env, feedUrl);
    for (const PodcastSubscription& subscription : GetPodcastSubscriptions()) {
        if (subscription.feedUrl == feed) return;
    }
    AddPodcastSubscription(ToWide(env, name), feed);
}

JNI(void, removePodcast)(JNIEnv*, jclass, jint identifier) {
    RemovePodcastSubscription(identifier);
}

// name, author, feed address; in threes. Blocks.
JNI(jobjectArray, searchPodcasts)(JNIEnv* env, jclass, jstring query) {
    std::vector<PodcastSearchResult> results;
    SearchItunesPodcasts(ToWide(env, query), results);
    std::vector<std::wstring> items;
    for (const PodcastSearchResult& result : results) {
        if (!result.feedUrl.empty()) items.insert(items.end(), {result.name, result.artistName, result.feedUrl});
    }
    return ToJavaArray(env, items);
}

// A feed's episodes, newest first as the feed has them. Blocks. The first two
// strings are the feed's title and an error ("" if it loaded); then title, date,
// summary, audio address and duration in seconds, in fives.
JNI(jobjectArray, loadEpisodes)(JNIEnv* env, jclass, jstring feedUrl) {
    std::wstring feed = ToWide(env, feedUrl);
    // A protected feed's credentials, if it is one subscribed to
    std::wstring username, password;
    for (const PodcastSubscription& subscription : GetPodcastSubscriptions()) {
        if (subscription.feedUrl == feed) {
            username = subscription.username;
            password = subscription.password;
        }
    }
    std::wstring title;
    std::vector<PodcastEpisode> episodes;
    PodcastFetchDiag diag;
    bool ok = ParsePodcastFeed(feed, title, episodes, username, password, &diag);
    std::vector<std::wstring> items{title, ok ? std::wstring() : BuildPodcastDiagMessage(feed, diag)};
    for (const PodcastEpisode& episode : episodes) {
        items.insert(items.end(), {episode.title, episode.pubDate, CleanPodcastDescription(episode.description),
                                   episode.audioUrl, std::to_wstring(episode.durationSeconds)});
    }
    return ToJavaArray(env, items);
}

// Plays the episodes (titles and addresses) as a playlist, starting with the one at `index`.
JNI(void, playEpisodes)(JNIEnv* env, jclass, jobjectArray titles, jobjectArray urls, jint index) {
    std::vector<std::wstring> names = ToWideList(env, titles);
    std::vector<std::wstring> addresses = ToWideList(env, urls);
    if (addresses.empty()) return;
    g_playlist.clear();
    for (size_t i = 0; i < addresses.size(); i++) {
        // An episode's address says nothing to a listener
        if (i < names.size()) SetTrackName(addresses[i], names[i]);
        g_playlist.push_back(addresses[i]);
    }
    g_currentTrack = -1;
    PlayTrack(std::clamp(static_cast<int>(index), 0, static_cast<int>(addresses.size()) - 1));
}
