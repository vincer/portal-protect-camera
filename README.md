# Portal Protect Camera

An experimental Android camera app that lets a Meta Portal send video and audio
directly to UniFi Protect, while also serving a direct RTSP stream for Home
Assistant or another local viewer. Capture, encoding, snapshots, and native
Protect transport all run on the Portal. An external relay computer is not
required in this mode.

This is an unofficial implementation of the native Protect camera protocol,
**not ONVIF**, a Ubiquiti product, or a general-purpose Android camera app. It is
a source snapshot of a working prototype, not a turnkey adoption installer.

## Current behavior

| Output | Video | Audio |
| --- | --- | --- |
| Native Protect transport | Shared H.264 Baseline, 1280×720, requested 30 fps, about 2 Mbps | AAC-LC plus optional Opus |
| Direct RTSP, TCP port 8556 | Same compressed H.264 | AAC-LC, 48 kHz, two channels, 64 kbps |
| Protect snapshots | JPEG from a recent H.264 keyframe, normally 640×360 | None |

Video is encoded once with Android MediaCodec. The microphone is read once;
its PCM feeds the AAC encoder and, when enabled, a separate mono Opus encoder
(48 kHz, 64 kbps, 20 ms packets). The bridge does not decode and re-encode AAC
or video. Snapshot requests briefly decode a keyframe and reuse the JPEG for
five seconds.

Protect's high/medium/low slots share the same encoded 720p source. They are
separate network outputs, not different resolutions or three video encoders.
Actual delivered frame rate can vary with firmware and camera behavior.

The tested capture path supplies identical left/right microphone samples;
two-channel AAC does not establish independent spatial stereo. Opus downmixes
those samples to mono. Existing device AI and auto-framing services are not
modified by this app.

### Audio modes

- **AAC + Opus:** the accepted normal configuration. Protect receives both;
  new recording exports contain AAC sound. The iPhone live test worked with
  reliable audio and close A/V sync.
- **Opus only:** an isolation test. Live sound worked consistently on the tested
  iPhone, but new Protect recordings/exported clips had no audio. Keep AAC if
  recording sound matters.
- **AAC only:** available when Opus is disabled. This was the original mode;
  iPhone live sound and relative A/V delay were less reliable in testing.

The Opus-only experiment demonstrates working Opus-originated live sound. The
phone's negotiated codec was not captured, so do not infer its exact decoder
or transport from a good screen recording alone. See [timing notes](docs/TIMING.md).

## Requirements and support boundary

Tested on one Meta Portal running Android 10, with local ADB access, camera and
microphone permission, and overlay permission. The native path was tested with
Protect 7.3.70 and the iPhone app 3.14.0. Other devices and versions are unverified.
Only the public camera interface is used: this build captures 720p, not 1080p or
4K. No root access, firmware modification, or changes to device AI are required.

Native mode also requires **an already-adopted, authorized native camera identity**
(client certificate/private key) and verified recorder certificate pins. Automated
identity creation/adoption is not included. Without that identity, build and use
RTSP mode first; the app does not adopt itself merely by being installed.

This development build is debuggable and its RTSP feed is unauthenticated and
unencrypted. Restrict it to a trusted LAN; it is not designed for Internet
exposure. See [security and privacy](SECURITY.md) before deploying it.

## Build

Use JDK 17, Android SDK platform 36, build-tools 37.0.0, platform-tools, and
`zip` on your build computer. No Gradle download or third-party Android library
is required.

```sh
export ANDROID_HOME="<ANDROID_SDK_DIRECTORY>"
bash app/build.sh
```

The result is `app/build/portal-protect-camera.apk`. The script creates a local
development signing key in `app/build/camera.keystore` if none exists. It uses
the conventional public development password `android`; that is not a supplied
production credential. Keep your own signing key to install future updates over
the same app. All build output and signing material are ignored by Git.

## Install and start RTSP

Use your existing authorized ADB connection. Disable another camera app before
starting this one if it competes for the camera or microphone.

```sh
export ANDROID_SERIAL="<PORTAL_ADB_SERIAL>"
adb install -r app/build/portal-protect-camera.apk
adb shell pm grant local.codex.portalprotectcamera android.permission.CAMERA
adb shell pm grant local.codex.portalprotectcamera android.permission.RECORD_AUDIO
adb shell appops set local.codex.portalprotectcamera SYSTEM_ALERT_WINDOW allow
adb shell am start -n local.codex.portalprotectcamera/.CameraActivity \
  --es protect_mode nuc
```

`nuc` is the historical mode name for **native transport disabled**. It starts
capture/RTSP and does not require a NUC or launch an external relay. Open:

```text
rtsp://<PORTAL_IP>:8556/
```

The endpoint supports RTP interleaved over RTSP/TCP. Opus is not added to this
endpoint; Home Assistant continues receiving AAC. A 1×1 transparent activity
and foreground notification keep capture available under the tested firmware's
visibility rules. The mode is persisted, and an enabled source restarts at boot.

To stop the source persistently:

```sh
adb shell am start -n local.codex.portalprotectcamera/.CameraActivity --ez stop true
```

## Enable native Protect transport

Provision your own identity as app-private `files/protect.p12` and configuration
as `files/protect.json`. The JSON format is illustrated in
[the placeholder-only example](examples/protect.example.json). It is deliberately
not a deployable credential file.

1. Obtain your authorized adopted identity outside this project. Use a PKCS#12
   container compatible with the Android firmware, with its local password.
2. Verify the recorder certificate fingerprints through a trusted channel. The
   app accepts a comma-separated list of SHA-256 leaf certificate fingerprints;
   never replace pinning with an accept-all trust manager.
3. Fill a private local copy of the example with your recorder hostname or IPv4
   address, Portal address, adopted 12-hex-digit identity, password, and pins.
   `host` must be a bare hostname/IPv4 address, not a URL; IPv6 is not supported
   by the current host validation.
4. Transfer the identity and configuration into app-private storage using your
   authorized local provisioning process. No credentials or provisioning secrets
   are included in this repository. Do not commit them.
5. Enable native mode:

```sh
adb shell am start -n local.codex.portalprotectcamera/.CameraActivity \
  --es protect_mode portal
```

Control uses pinned mutual TLS on port 7442; media is pushed to the configured
recorder on TCP port 7550. Snapshots upload over pinned HTTPS. Native media itself
is unencrypted. No Home Assistant or recorder administrator password is needed
by the runtime app.

Changing the private JSON requires an app restart to recreate its encoders and
reload the configuration:

```sh
adb shell am force-stop local.codex.portalprotectcamera
adb shell am start -n local.codex.portalprotectcamera/.CameraActivity \
  --es protect_mode portal
```

For normal dual-codec operation, set `enableOpus: true` and
`protectOpusOnly: false`. If the device has no working Opus encoder, it does not
advertise Opus. Never copy another installation's identity or certificate pins.

## Tests

JDK tools, Python 3, and FFmpeg/FFprobe with `libx264` and `libopus` are needed.
These defaults generate temporary synthetic video/audio; no camera, credentials,
network connection, or committed recordings are required.

```sh
python3 tests/test_muxer.py
python3 tests/test_opus.py
```

The checks cover extended-FLV tags/trailers, timestamp origins, periodic clock
mapping, per-track monotonicity, AAC decoding, startup suppression versus timestamp
advance, bounded preroll, Opus packet preservation, Opus-only AAC exclusion, and
Opus decoding. Android's hardware/media implementations still require a device
test; synthetic FFmpeg output is not proof of an Android encoder's behavior.

Optional hardware input is explicit: `SOURCE_RTSP` selects a six-second live
capture for the muxer test; `SOURCE_MEDIA` selects an existing local H.264/AAC MP4.
`OPUS_FIXTURE` supplies the binary output of `tests/CodecProbe.java` from a real
Android encoder instead of the synthetic Opus fixture. The Java probe generates
a tone and does not open the microphone. Its binary format is repeated big-endian
`int64 pts_us`, `uint32 length`, followed by that many raw Opus bytes. Test media
is kept in temporary directories and removed on completion.

## Source map and limitations

See [architecture](docs/ARCHITECTURE.md), [configuration](docs/CONFIGURATION.md),
[timing](docs/TIMING.md), [review notes](docs/REVIEW.md), and the
[publication audit](docs/PUBLICATION_AUDIT.md).

`app/` contains the Android source and protocol templates; `tests/` contains Java
fixtures and Python protocol/decoder checks. The code is intentionally close to
the prototype. There is no supported production installer, general ONVIF server,
automated adoption, talkback, or implemented Protect smart-detection pipeline.
Some control templates advertise compatibility fields and acknowledge settings
without applying them. Firmware update requests are ignored. Reconnect behavior
was exercised; a post-migration device reboot and broad device compatibility
matrix have not been validated.

## Attribution and license

MIT license. Native protocol response templates derive from
[unifi-cam-proxy](https://github.com/keshavdv/unifi-cam-proxy), copyright Keshav
Varma, with the notice retained in `app/assets/THIRD-PARTY-LICENSE.txt`.
The Opus type-10 wire format was cross-checked against
[scrypted-unifi-direct](https://github.com/xuio/scrypted-unifi-direct/blob/main/src/native-rtsp.ts).
Third-party implementations and UniFi firmware may change independently.
