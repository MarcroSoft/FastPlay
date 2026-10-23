// Audio CDs on Windows. Windows shows each track of a disc as a .cda file (Track01.cda),
// a 44 byte RIFF "CDDA" stub that says where the track lies on the disc; the audio
// itself is read from the drive as raw sectors (2352 bytes, 588 frames of 16 bit
// stereo at 44.1 kHz). The title and artist come from the disc's CD-Text, if it has any.

#include "audio.h"
#include "audio_internal.h"
#include "utils.h"

#include <windows.h>
#include <winioctl.h>
#include <ntddcdrm.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <vector>

namespace audio {
namespace {

const int kRate = 44100;
const int kSectorBytes = 2352;
const int kSectorFrames = kSectorBytes / 4;
const int kSectorsPerRead = 24;  // well under what any drive takes in one transfer
const int kCdTextAlbum = 0;      // CD-Text's "track 0" is the disc itself

// What a .cda file says about its track (all little-endian)
#pragma pack(push, 1)
struct CdaFile {
    char riff[4];        // "RIFF"
    uint32_t riffSize;
    char cdda[4];        // "CDDA"
    char fmt[4];         // "fmt "
    uint32_t fmtSize;    // 24
    uint16_t version;
    uint16_t track;
    uint32_t serial;
    uint32_t startSector;   // from the start of the disc's audio, without the 2 second lead-in
    uint32_t lengthSectors;
    uint8_t startMsf[4];
    uint8_t lengthMsf[4];
};
#pragma pack(pop)

std::string Latin1ToUtf8(const std::string& text) {
    std::string out;
    for (unsigned char c : text) {
        if (c < 0x80) {
            out += static_cast<char>(c);
        } else {
            out += static_cast<char>(0xC0 | (c >> 6));
            out += static_cast<char>(0x80 | (c & 0x3F));
        }
    }
    return out;
}

class CdDecoder : public Decoder {
public:
    ~CdDecoder() override {
        if (m_drive != INVALID_HANDLE_VALUE) CloseHandle(m_drive);
    }

    bool Open(const std::wstring& path, std::wstring& error) {
        CdaFile cda = {};
        FILE* f = FileOpen(path, "rb");
        if (!f) {
            error = L"The file could not be opened.";
            return false;
        }
        size_t got = fread(&cda, 1, sizeof(cda), f);
        fclose(f);
        if (got < sizeof(cda) || memcmp(cda.riff, "RIFF", 4) != 0 || memcmp(cda.cdda, "CDDA", 4) != 0) {
            error = L"It is not a CD audio track.";
            return false;
        }
        m_track = cda.track;
        m_start = cda.startSector;
        m_sectors = cda.lengthSectors;

        // The drive the .cda file is on: \\.\E:
        wchar_t volume[MAX_PATH] = {};
        if (!GetVolumePathNameW(path.c_str(), volume, MAX_PATH) || GetDriveTypeW(volume) != DRIVE_CDROM) {
            error = L"A CD track plays only from the CD drive it is on.";
            return false;
        }
        std::wstring device = L"\\\\.\\" + std::wstring(volume);
        if (!device.empty() && device.back() == L'\\') device.pop_back();
        m_drive = CreateFileW(device.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr,
                              OPEN_EXISTING, 0, nullptr);
        if (m_drive == INVALID_HANDLE_VALUE) {
            error = L"The CD drive could not be opened.";
            return false;
        }
        ReadCdText();
        return true;
    }

    int SampleRate() const override { return kRate; }

    int Read(float* out, int frames) override {
        int done = 0;
        while (done < frames) {
            if (m_bufferPos >= m_bufferFrames) {
                if (m_next >= m_sectors || m_abort) break;
                if (!Fill()) break;
            }
            int n = std::min(frames - done, m_bufferFrames - m_bufferPos);
            const int16_t* in = m_buffer.data() + static_cast<size_t>(m_bufferPos) * 2;
            for (int i = 0; i < n * 2; i++) out[done * 2 + i] = in[i] / 32768.0f;
            m_bufferPos += n;
            done += n;
        }
        return done;
    }

    bool Seek(double seconds) override {
        int64_t frame = static_cast<int64_t>(seconds * kRate);
        if (frame < 0) frame = 0;
        int64_t sector = frame / kSectorFrames;
        if (sector >= m_sectors) {
            m_next = m_sectors;
            m_bufferPos = m_bufferFrames = 0;
            return true;
        }
        m_next = static_cast<uint32_t>(sector);
        m_bufferPos = m_bufferFrames = 0;
        m_skip = static_cast<int>(frame % kSectorFrames);
        m_abort = false;
        return true;
    }

    void Abort() override { m_abort = true; }
    double Length() const override { return static_cast<double>(m_sectors) * kSectorFrames / kRate; }
    bool IsLive() const override { return false; }

    std::string Tag(const std::string& name) const override {
        const char* key = name.c_str();
        if (StrICmp(key, "TITLE") == 0) return m_title;
        if (StrICmp(key, "ARTIST") == 0) return m_artist;
        if (StrICmp(key, "ALBUM") == 0) return m_album;
        if (StrICmp(key, "ALBUMARTIST") == 0 || StrICmp(key, "ALBUM_ARTIST") == 0) return m_albumArtist;
        if (StrICmp(key, "TRACK") == 0) return std::to_string(m_track);
        return "";
    }

    std::string StreamTitle() const override { return ""; }
    std::vector<Chapter> Chapters() const override { return {}; }
    int Bitrate() const override { return 1411; }
    bool IsVbr() const override { return false; }
    int SourceChannels() const override { return 2; }
    int SourceSampleRate() const override { return kRate; }
    int SourceBits() const override { return 16; }
    std::string CodecName() const override { return "CD Audio"; }

private:
    // The next sectors into the buffer. A sector the drive cannot read (a scratch)
    // plays as silence; a run of them in a row (the disc taken out) ends the track.
    bool Fill() {
        int count = static_cast<int>(std::min<uint32_t>(kSectorsPerRead, m_sectors - m_next));
        m_buffer.resize(static_cast<size_t>(count) * kSectorFrames * 2);
        RAW_READ_INFO info = {};
        info.DiskOffset.QuadPart = static_cast<LONGLONG>(m_start + m_next) * 2048;
        info.SectorCount = count;
        info.TrackMode = CDDA;
        DWORD bytes = 0;
        bool ok = false;
        for (int attempt = 0; attempt < 3 && !ok && !m_abort; attempt++) {
            ok = DeviceIoControl(m_drive, IOCTL_CDROM_RAW_READ, &info, sizeof(info), m_buffer.data(),
                                 static_cast<DWORD>(m_buffer.size() * sizeof(int16_t)), &bytes, nullptr) != 0;
        }
        if (m_abort) return false;
        if (ok) {
            m_failures = 0;
            count = static_cast<int>(bytes / kSectorBytes);
            if (count == 0) return false;
        } else {
            if (++m_failures >= 8) return false;
            std::fill(m_buffer.begin(), m_buffer.end(), int16_t(0));
        }
        m_next += count;
        m_bufferFrames = count * kSectorFrames;
        m_bufferPos = std::min(m_skip, m_bufferFrames);
        m_skip = 0;
        return true;
    }

    // Titles and performers from the disc's CD-Text (its first block, which is
    // Latin-1 nearly always). Each kind of text runs through its packs, one
    // string per track ending in a zero; a tab means "the same as the last".
    void ReadCdText() {
        CDROM_READ_TOC_EX request = {};
        request.Format = CDROM_READ_TOC_EX_FORMAT_CDTEXT;
        // First the header (CDROM_TOC_CD_TEXT_DATA without its blocks) for the length
        const size_t kHeader = 4;
        uint8_t header[kHeader] = {};
        DWORD bytes = 0;
        if (!DeviceIoControl(m_drive, IOCTL_CDROM_READ_TOC_EX, &request, sizeof(request), header, sizeof(header),
                             &bytes, nullptr)) {
            return;
        }
        size_t length = ((static_cast<size_t>(header[0]) << 8) | header[1]) + 2;
        if (length <= kHeader) return;
        std::vector<uint8_t> data(length);
        if (!DeviceIoControl(m_drive, IOCTL_CDROM_READ_TOC_EX, &request, sizeof(request), data.data(),
                             static_cast<DWORD>(data.size()), &bytes, nullptr) ||
            bytes <= kHeader) {
            return;
        }
        size_t count = (std::min<size_t>(bytes, data.size()) - kHeader) / sizeof(CDROM_TOC_CD_TEXT_DATA_BLOCK);
        auto* blocks = reinterpret_cast<const CDROM_TOC_CD_TEXT_DATA_BLOCK*>(data.data() + kHeader);

        auto text = [&](UCHAR packType, int track) -> std::string {
            int current = -1;
            std::string value, last;
            for (size_t i = 0; i < count; i++) {
                const auto& b = blocks[i];
                if (b.PackType != packType || b.BlockNumber != 0 || b.Unicode) continue;
                if (current < 0) current = b.TrackNumber;
                for (int c = 0; c < 12; c++) {
                    char ch = static_cast<char>(b.Text[c]);
                    if (ch != '\0') {
                        value += ch;
                        continue;
                    }
                    if (value == "\t") value = last;
                    if (current == track) return Latin1ToUtf8(value);
                    last = value;
                    value.clear();
                    current++;
                }
            }
            return "";
        };
        m_title = text(CDROM_CD_TEXT_PACK_ALBUM_NAME, m_track);
        m_artist = text(CDROM_CD_TEXT_PACK_PERFORMER, m_track);
        m_album = text(CDROM_CD_TEXT_PACK_ALBUM_NAME, kCdTextAlbum);
        m_albumArtist = text(CDROM_CD_TEXT_PACK_PERFORMER, kCdTextAlbum);
        if (m_artist.empty()) m_artist = m_albumArtist;
    }

    HANDLE m_drive = INVALID_HANDLE_VALUE;
    int m_track = 0;
    uint32_t m_start = 0, m_sectors = 0;
    uint32_t m_next = 0;  // the next sector to read, from the track's start
    std::vector<int16_t> m_buffer;
    int m_bufferFrames = 0, m_bufferPos = 0;
    int m_skip = 0;  // frames into the next sector read, after a seek
    int m_failures = 0;
    std::atomic<bool> m_abort{false};
    std::string m_title, m_artist, m_album, m_albumArtist;
};

}  // namespace

std::unique_ptr<Decoder> OpenCdDecoder(const std::wstring& path, std::wstring& error) {
    auto decoder = std::make_unique<CdDecoder>();
    if (!decoder->Open(path, error)) return nullptr;
    return decoder;
}

}  // namespace audio
