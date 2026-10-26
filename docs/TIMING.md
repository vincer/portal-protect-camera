# Audio/video timing and what was tested

All encoded source timestamps share a monotonic microsecond origin. Native sinks
select a video keyframe as relative zero and add a 30000 ms transport origin.
This origin leaves room for audio timestamps advanced before relative zero.
The 75 ms advance changes timestamps for AAC and Opus; it is not a sleep or a
new PCM buffer. The video payload and timestamps are not shifted by this setting.

The current accepted profile is 75 ms advance, no startup drop, retained startup
packets, zero genuine preroll, and 250 ms clock-sync reports. Keep this combination
explicit rather than relying on legacy fallbacks.

| AAC-era timestamp advance | User-observed live sound |
| --- | --- |
| 0 ms | Intermittent silence, reproduced |
| 25 ms | Failed |
| 50 ms | Failed |
| 75 ms | Worked, then worked again after the failed 50 ms trial |
| 100 ms | Worked |
| 300 ms | Worked |
| 750 ms | Worked |

Dropping the first 750 ms of audio without timestamp advance still failed. A
separate genuine-preroll experiment with about 200 ms of original-PTS AAC also
failed to make live audio consistently reliable. It was disabled. These are
observations from one setup, not universal AAC requirements or proof of a
specific iPhone implementation defect. The 75 ms setting was retained when Opus
was introduced; Opus at zero advance has not been established as equivalent.

Native Opus is 48 kHz mono with 20 ms packets (960 clock ticks). Actual Protect
restream packets and decoded sound were checked, not just the capability list.
In the controlled Opus-only interval, the user confirmed consistent iPhone live
sound with close sync, while recorded content had no sound. Both codecs were
then restored. Do not remove AAC for normal recorded audio on the tested version.

A short post-Opus screen recording contained three snaps with roughly 50–100 ms
of audio lateness relative to the visible snap. An earlier recording showed
roughly 580 ms median lateness after correcting for its half-speed export. These
are relative A/V observations with visual frame uncertainty; they do not measure
absolute camera-to-phone latency or prove which codec a phone session negotiated.
No screen recordings, PCM, packet captures, or personal scene data are published.

The installed web client checks Opus support when selecting a realtime media-track
player for cameras with microphones. This is a concrete web implementation clue;
the iPhone's negotiated codec/player was not captured. Opus-originated sound
working through the recorder is stronger evidence than a capability declaration,
but does not by itself rule out recorder-side conversion before the phone.

Recorder wall-time mapping uses an HTTPS Date reference rather than changing the
Portal's system clock. Whole-second Date precision and unmeasured network delay
limit absolute accuracy; common wall-clock error alone does not explain relative
A/V skew. Some independent live API joins exhibited an initial timestamp mapping
transition. A clean recording export is not proof of the actual phone live session:
exports may rebase tracks, and the diagnostic API client is a separate consumer.
