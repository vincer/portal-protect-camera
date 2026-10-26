# Architecture

```text
Camera2 -> one H.264 hardware encoder ---+--> RTSP/TCP -> local viewer / HA
                                       +--> Protect video1/video2/video3 sinks
                                       +--> on-demand JPEG decoder/cache
Microphone -> shared PCM sample clock --+--> AAC encoder --> RTSP + Protect
                                       +--> Opus encoder -> Protect (optional)
```

`CameraService` owns capture and RTSP. Encoded frames contain microsecond PTS
relative to one monotonic origin; the corresponding wall origin is stored once.
Audio PTS advances by submitted sample count. Microphone reads are 40 ms; Opus
input is split into at most 20 ms mono blocks. Encoder drain threads fan out
immutable encoded payloads. Slow consumers have bounded queues and reconnect
rather than growing an unlimited history.

`ProtectBridge` loads local identity/config and sends the native control handshake
using `ProtectWebSocket`. Feature advertisement includes Opus only after a real
packet has been produced. It accepts only media destinations on the configured
recorder host and expected port. Each requested video slot waits for a keyframe,
rebases to its local stream origin, then sends AAC and requested Opus according
to the configured mode. Source packets are not re-encoded by these sinks.

`ProtectMuxer` serializes H.264/AAC into an extended FLV-like stream. Each normal
FLV tag is followed by the usual previous-tag size and a 16-byte native clock
trailer. Script tags report wall-clock mapping at a configured interval. Opus
uses native tag type 10, a CF000300 configuration payload, and then raw Opus
packets. This type is not standard FLV audio. Tests reconstruct ordinary FLV for
AAC/video decoding and temporarily wrap Opus packets in Ogg for decoding.

`ProtectSnapshot` decodes a recent keyframe only when asked, downsamples it,
converts YUV planes to NV21, produces JPEG, and caches it. It does not run a
second camera or continuous video decoder. Snapshot work is serialized and its
request queue is bounded.

`CameraActivity` stores source/native-mode preferences; it is a small transparent
window. `BootReceiver` honors the persisted source-enabled preference. Camera
recovery retries after five seconds; native control reconnects with bounded
backoff. RTSP remains independent of native-mode switching.

A local relay computer can remain useful for building/provisioning or analyzing
media, but is not part of this native runtime path. The published source does
not include the earlier ONVIF/relay experiments or private operational scripts.
