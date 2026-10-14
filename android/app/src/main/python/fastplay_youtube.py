"""
FastPlay's YouTube, through yt-dlp used as a library: searches, the videos of
channels and playlists, getting a video's audio ready to play, downloads, and
keeping yt-dlp itself up to date.

YouTube keeps changing, and an old yt-dlp soon stops working with it. The APK
carries a yt-dlp (installed by pip when the app is built), and FastPlay checks
yt-dlp's latest release at most once a day: when it is newer, its single-file
build (a zip of the Python code) is downloaded into the app's files and put first
on sys.path, so it is used instead. A download that is missing, broken or older
than the APK's leaves the APK's in use.

YouTube sets challenges that need a JavaScript runtime to answer: QuickJS, built
with the app as libqjs.so in its native library folder (CMakeLists.txt).

Kotlin (YtDlp.kt) calls these from worker threads. Each returns JSON text: what
was asked for, or an object with "error" saying what went wrong.
"""

import hashlib
import importlib
import importlib.metadata
import json
import os
import re
import sys
import tempfile
import threading
import time
import urllib.request
import zipfile

RELEASES = 'https://github.com/yt-dlp/yt-dlp/releases/latest/download/'
# The release asset that is the single-file build: Python code in a zip
ZIPAPP = 'yt-dlp'
DAY = 24 * 60 * 60
WEEK = 7 * DAY

_paths = {}
_yt_dlp = None
_source = 'bundled'

# Calls in progress, so a new yt-dlp is put in use only between them
_state = threading.Condition()
_active = 0
_reload_pending = False


# ---------------------------------------------------------------------------
# Starting, and which yt-dlp
# ---------------------------------------------------------------------------

def setup(files_dir, cache_dir, qjs_path):
    """Where things go, and yt-dlp loaded. Returns the version in use."""
    tools = os.path.join(files_dir, 'yt-dlp')
    temp = os.path.join(cache_dir, 'yt-dlp-temp')
    for folder in (tools, temp):
        os.makedirs(folder, exist_ok=True)
    _paths.update(
        tools=tools,
        cache=os.path.join(cache_dir, 'yt-dlp-cache'),
        audio=os.path.join(cache_dir, 'YouTube'),
        cookies=os.path.join(files_dir, 'youtube-cookies.txt'),
        qjs=qjs_path,
    )
    # QuickJS reads its scripts from files: here
    tempfile.tempdir = temp
    os.makedirs(_paths['audio'], exist_ok=True)
    _clean_audio()
    _load()
    return version()


def _version_tuple(text):
    return tuple(int(part) for part in re.findall(r'\d+', text or ''))


def _bundled_version():
    try:
        return importlib.metadata.version('yt-dlp')
    except Exception:
        pass
    # Without its package metadata: from the APK's yt-dlp itself
    try:
        _unload()
        return importlib.import_module('yt_dlp.version').__version__
    except Exception:
        return ''


def _zip_version(path):
    """The version of a downloaded yt-dlp, or '' if it is not one."""
    try:
        with zipfile.ZipFile(path) as archive:
            text = archive.read('yt_dlp/version.py').decode('utf-8')
        match = re.search(r"__version__\s*=\s*'([^']+)'", text)
        return match.group(1) if match else ''
    except Exception:
        return ''


def _downloaded():
    """The downloaded yt-dlp, if there is one: (path, version)."""
    for name in sorted(os.listdir(_paths['tools'])):
        if name.startswith('yt-dlp-') and name.endswith('.zip'):
            path = os.path.join(_paths['tools'], name)
            return path, _zip_version(path)
    return None, ''


def _unload():
    for name in list(sys.modules):
        if name == 'yt_dlp' or name.startswith('yt_dlp.') or name == 'yt_dlp_ejs' or name.startswith('yt_dlp_ejs.'):
            del sys.modules[name]
    for path in list(sys.path):
        if path.startswith(_paths['tools']):
            sys.path.remove(path)
    importlib.invalidate_caches()


def _load():
    """Imports yt-dlp: the downloaded one when it is newer than the APK's and works."""
    global _yt_dlp, _source
    path, found = _downloaded()
    bundled = _bundled_version() if path else ''
    _unload()
    if path and (not found or _version_tuple(found) <= _version_tuple(bundled)):
        # Broken, or the APK has caught up with it
        _remove(path)
        path = None
    if path:
        sys.path.insert(0, path)
        try:
            _yt_dlp = importlib.import_module('yt_dlp')
            importlib.import_module('yt_dlp.version')
            _source = 'downloaded'
            return
        except Exception as error:
            print(f'The downloaded yt-dlp did not load: {error!r}', file=sys.stderr)
            _unload()
            _remove(path)
    _yt_dlp = importlib.import_module('yt_dlp')
    importlib.import_module('yt_dlp.version')
    _source = 'bundled'


def _remove(path):
    try:
        os.remove(path)
    except OSError:
        pass


def version():
    return json.dumps({'version': _yt_dlp.version.__version__, 'source': _source})


class _Use:
    """Around each call that uses yt-dlp: a new one waiting is put in use first."""

    def __enter__(self):
        global _active, _reload_pending
        with _state:
            if _reload_pending and _active == 0:
                _reload_pending = False
                _load()
            _active += 1

    def __exit__(self, *exc):
        global _active
        with _state:
            _active -= 1
            _state.notify_all()


# ---------------------------------------------------------------------------
# Keeping yt-dlp up to date
# ---------------------------------------------------------------------------

def _fetch(url, timeout=30):
    request = urllib.request.Request(url, headers={'User-Agent': 'FastPlay'})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def update(force):
    """
    Checks yt-dlp's latest release (unless it was checked within the day, or
    force) and, when it is newer than the yt-dlp in use, downloads it and puts it
    in use. Returns {"version", "source", "updated", "checked"}.
    """
    global _reload_pending
    stamp = os.path.join(_paths['tools'], 'checked')
    try:
        recent = time.time() - os.path.getmtime(stamp) < DAY
    except OSError:
        recent = False
    current = json.loads(version())
    if recent and not force:
        return json.dumps(dict(current, updated=False, checked=False))

    try:
        # The release's checksum for the file: a new one means a new release
        sums = _fetch(RELEASES + 'SHA2-256SUMS', 20).decode('utf-8', 'replace')
        latest = ''
        for line in sums.splitlines():
            parts = line.strip().split()
            if len(parts) == 2 and parts[1].lstrip('*') == ZIPAPP:
                latest = parts[0].lower()
        if not latest:
            raise RuntimeError('The release lists no yt-dlp.')
        try:
            with open(stamp) as f:
                seen = f.read().strip()
        except OSError:
            seen = ''
        if latest == seen and not force:
            _touch(stamp, latest)
            return json.dumps(dict(current, updated=False, checked=True))

        part = os.path.join(_paths['tools'], 'download.part')
        data = _fetch(RELEASES + ZIPAPP, 120)
        if hashlib.sha256(data).hexdigest() != latest:
            raise RuntimeError('The download was damaged.')
        with open(part, 'wb') as f:
            f.write(data)
        found = _zip_version(part)
        _touch(stamp, latest)
        if not found or _version_tuple(found) <= _version_tuple(current['version']):
            _remove(part)
            return json.dumps(dict(current, updated=False, checked=True))
        for name in os.listdir(_paths['tools']):
            if name.startswith('yt-dlp-') and name.endswith('.zip'):
                _remove(os.path.join(_paths['tools'], name))
        os.replace(part, os.path.join(_paths['tools'], f'yt-dlp-{found}.zip'))
    except Exception as error:
        # Tried: again tomorrow, not on every video
        _touch(stamp, None)
        return json.dumps(dict(current, updated=False, checked=True, problem=_message(error)))

    with _state:
        _reload_pending = True
        if _active == 0:
            _reload_pending = False
            _load()
    return json.dumps({'version': found, 'source': 'downloaded', 'updated': True, 'checked': True})


def _touch(stamp, text):
    if text is not None:
        with open(stamp, 'w') as f:
            f.write(text)
    elif not os.path.exists(stamp):
        open(stamp, 'w').close()
    os.utime(stamp, None)


# ---------------------------------------------------------------------------
# Running yt-dlp
# ---------------------------------------------------------------------------

class _Log:
    """yt-dlp's messages: errors kept for the user, warnings to the log."""

    def __init__(self):
        self.errors = []

    def debug(self, message):
        pass

    def info(self, message):
        pass

    def warning(self, message):
        print(f'yt-dlp: {message}', file=sys.stderr)

    def error(self, message):
        self.errors.append(message)


def _options(log, **more):
    options = {
        'quiet': True,
        'noprogress': True,
        'logger': log,
        'cachedir': _paths['cache'],
        'socket_timeout': 20,
        'noplaylist': True,
    }
    if _paths.get('qjs'):
        options['js_runtimes'] = {'quickjs': {'path': _paths['qjs']}}
    if os.path.isfile(_paths['cookies']):
        options['cookiefile'] = _paths['cookies']
    options.update(more)
    return options


_ANSI = re.compile(r'\x1b\[[0-9;]*m')


def _message(error, log=None):
    """What went wrong, for the user: yt-dlp's last ERROR line, as on the desktop."""
    text = ''
    if log and log.errors:
        text = log.errors[-1]
    if not text:
        text = str(error) or error.__class__.__name__
    text = _ANSI.sub('', text).strip()
    return text


def _run(target, log, download=False, process=True, **options):
    with _Use():
        with _yt_dlp.YoutubeDL(_options(log, **options)) as ydl:
            return ydl.extract_info(target, download=download, process=process)


def _duration(seconds, live):
    if live:
        return 'live'
    try:
        seconds = int(float(seconds))
    except (TypeError, ValueError):
        return ''
    hours, rest = divmod(seconds, 3600)
    minutes, seconds = divmod(rest, 60)
    return f'{hours}:{minutes:02d}:{seconds:02d}' if hours else f'{minutes}:{seconds:02d}'


def _is_channel_id(text):
    return bool(text) and len(text) == 24 and text.startswith('UC')


# ---------------------------------------------------------------------------
# Searching and listing
# ---------------------------------------------------------------------------

def _entry(entry):
    """A search result or listing entry, as the desktop's YouTubeResult."""
    kind = 'video'
    item_id = entry.get('id') or ''
    title = entry.get('title') or ''
    channel = entry.get('channel') or entry.get('uploader') or ''
    channel_id = entry.get('channel_id') or ''
    duration = ''
    if entry.get('ie_key') == 'YoutubeTab':
        # A channel or playlist among search results
        if _is_channel_id(item_id):
            kind = 'channel'
            channel_id = item_id
            channel = channel or title
        else:
            kind = 'playlist'
    else:
        duration = _duration(entry.get('duration'), entry.get('live_status') == 'is_live')
    return {'kind': kind, 'id': item_id, 'title': title, 'channel': channel, 'channelId': channel_id,
            'duration': duration}


def _list(target, start, count):
    """One page of a flat listing; a "{end}" in target is the number of its last entry."""
    end = start + count - 1
    target = target.replace('{end}', str(end))
    log = _Log()
    try:
        info = _run(target, log, extract_flat='in_playlist', playlist_items=f'{start}:{end}', noplaylist=False)
    except Exception as error:
        return json.dumps({'error': _message(error, log)})
    results = []
    for entry in info.get('entries') or []:
        if not entry or not entry.get('id') or not entry.get('title'):
            continue
        results.append(_entry(entry))
    found = len(list(info.get('entries') or []))
    return json.dumps({
        'results': results,
        'more': found >= count,
        'title': info.get('title') or '',
        'id': info.get('id') or '',
        'channel': info.get('channel') or info.get('uploader') or '',
        'channelId': info.get('channel_id') or '',
    })


def search(query, start, count):
    return _list('ytsearch{end}:' + query, start, count)


def list_videos(url, start, count):
    return _list(url, start, count)


def identify(url):
    """What channel or playlist a page is: {kind, id, name, channel}."""
    log = _Log()
    try:
        info = _run(url, log, extract_flat='in_playlist', playlist_items='0', noplaylist=False)
    except Exception as error:
        return json.dumps({'error': _message(error, log)})
    item_id = info.get('id') or ''
    channel_id = info.get('channel_id') or ''
    if not item_id:
        return json.dumps({'error': 'This is not a YouTube channel or playlist.'})
    if _is_channel_id(item_id) or (channel_id and item_id == channel_id) or item_id.startswith('@'):
        # A channel page ("Name - Videos" is the tab's title; the channel's name is plainer)
        found = item_id if _is_channel_id(item_id) else channel_id
        if not _is_channel_id(found):
            return json.dumps({'error': "YouTube did not give this channel's ID."})
        name = info.get('channel') or info.get('uploader') or info.get('title') or ''
        return json.dumps({'kind': 'channel', 'id': found, 'name': name, 'channel': ''})
    return json.dumps({'kind': 'playlist', 'id': item_id, 'name': info.get('title') or '',
                       'channel': info.get('channel') or info.get('uploader') or ''})


def video_channel(video_id):
    """The channel a video belongs to: {kind, id, name}."""
    log = _Log()
    try:
        # (Only what the page says: its streams, and the player script, are not needed)
        info = _run('https://www.youtube.com/watch?v=' + video_id, log, process=False,
                    extractor_args={'youtube': {'player_skip': ['js']}})
    except Exception as error:
        return json.dumps({'error': _message(error, log)})
    channel_id = info.get('channel_id') or ''
    if not _is_channel_id(channel_id):
        return json.dumps({'error': 'YouTube did not say which channel this video is from.'})
    return json.dumps({'kind': 'channel', 'id': channel_id, 'name': info.get('channel') or info.get('uploader') or '',
                       'channel': ''})


# ---------------------------------------------------------------------------
# Getting a video ready to play
# ---------------------------------------------------------------------------

def _clean_audio():
    """Audio downloaded to play, not played for a week, and what was left half done."""
    now = time.time()
    try:
        names = os.listdir(_paths['audio'])
    except OSError:
        return
    for name in names:
        path = os.path.join(_paths['audio'], name)
        try:
            if '.download.' in name or now - os.path.getmtime(path) > WEEK:
                os.remove(path)
        except OSError:
            pass


def _file_name(title):
    name = ''.join('_' if ord(c) < 32 or c in '\\/:*?"<>|' else c for c in title)[:120].rstrip(' .')
    return name or 'YouTube'


def _cached(video_id):
    suffix = f' [{video_id}].m4a'
    for name in os.listdir(_paths['audio']):
        if name.endswith(suffix):
            return os.path.join(_paths['audio'], name), name[:-len(suffix)]
    return None, ''


def resolve(video_id):
    """
    A video ready to play: {title, channel, url} with YouTube's HLS audio, which
    FFmpeg plays as it arrives, with seeking (a live stream: its smallest HLS
    video, whose audio is played). For the rare video without it, the AAC audio
    is downloaded instead and {title, channel, file} says where (kept for a week).
    """
    url = 'https://www.youtube.com/watch?v=' + video_id
    # First without YouTube's player script: its challenges take QuickJS many
    # seconds (a phone's even more), and the HLS audio rarely needs them. Then
    # with it, if that found nothing.
    error = ''
    for skip in (['js'], []):
        log = _Log()
        args = {'extractor_args': {'youtube': {'player_skip': skip}}} if skip else {}
        try:
            info = _run(url, log, format='234/233/93/92/91/94/95', **args)
            stream = info.get('url') or ''
            if stream:
                return json.dumps({
                    'title': info.get('title') or '',
                    'channel': info.get('channel') or info.get('uploader') or '',
                    'url': stream,
                    'live': info.get('live_status') == 'is_live',
                    'duration': info.get('duration') or 0,
                })
            error = 'YouTube gave no audio for this video.'
        except Exception as failure:
            error = _message(failure, log)
    if 'Requested format is not available' not in error:
        return json.dumps({'error': error})

    # Nothing to stream: the audio downloaded, and played from the file
    path, title = _cached(video_id)
    if path:
        os.utime(path, None)  # kept another week
        return json.dumps({'title': title, 'channel': '', 'file': path})
    download = os.path.join(_paths['audio'], video_id + '.download.m4a')
    _remove(download)
    log = _Log()
    try:
        info = _run(url, log, format='140/bestaudio[ext=m4a]', outtmpl=download, fixup='never', nopart=True,
                    updatetime=False, download=True)
    except Exception as failure:
        _remove(download)
        return json.dumps({'error': _message(failure, log)})
    if not os.path.isfile(download):
        return json.dumps({'error': 'YouTube gave no audio for this video.'})
    title = info.get('title') or video_id
    path = os.path.join(_paths['audio'], f'{_file_name(title)} [{video_id}].m4a')
    os.replace(download, path)
    return json.dumps({'title': title, 'channel': info.get('channel') or info.get('uploader') or '',
                       'file': path, 'downloaded': True})


# ---------------------------------------------------------------------------
# Downloads to keep
# ---------------------------------------------------------------------------

def download(url, folder, playlist):
    """
    A video's audio (or a whole playlist's) downloaded into folder as YouTube's own
    M4A, without converting: {"files": [...]} with where each one went.
    """
    files = []
    name = '%(title)s.%(ext)s'
    if playlist:
        name = '%(playlist_title,playlist)s/' + name
    log = _Log()
    try:
        _run(url, log, format='bestaudio[ext=m4a]/bestaudio', paths={'home': folder}, outtmpl=name,
             fixup='never', noplaylist=not playlist, updatetime=False, ignoreerrors=playlist,
             post_hooks=[files.append], download=True)
    except Exception as error:
        if not files:
            return json.dumps({'error': _message(error, log)})
    if not files:
        return json.dumps({'error': _message(None, log) if log.errors else 'Nothing was downloaded.'})
    return json.dumps({'files': files, 'failed': len(log.errors)})
