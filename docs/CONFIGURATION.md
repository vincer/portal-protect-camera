# Private native configuration

The runtime reads `files/protect.json` and `files/protect.p12` inside the Android
app's private directory. No deployment values are supplied by the repository.

| JSON field | Meaning |
| --- | --- |
| `host` | Bare configured recorder hostname or IPv4 address. Also restricts media/snapshot destinations. |
| `ip` | Portal address reported in the native handshake. |
| `mac` | Existing authorized adopted identity, 12 hex digits without separators. |
| `keyPassword` | Local password for the provisioned PKCS#12 identity. |
| `serverSha256` | Comma-separated verified SHA-256 leaf certificate fingerprints. |
| `enableOpus` | Start a parallel Opus encoder; default false. Requires restart. |
| `protectOpusOnly` | Omit AAC from native output when Opus is enabled; default false. Test mode loses recorded audio on the tested recorder. |
| `audioAdvanceMs` | Timestamp advance for native audio. Accepted profile 75; legacy fallback 750. |
| `audioStartupDropMs` | Explicit initial audio suppression; accepted profile 0. |
| `audioRetainStartup` | Permit audio before the muxer's relative zero after timestamp advance; accepted profile true. Legacy default false. |
| `audioPrerollMs` | Genuine compressed AAC history before selected video keyframe; accepted profile 0. Experiment accepts 1–500. |
| `mediaClockOriginMs` | Positive native timestamp origin, default 30000. |
| `clockSyncIntervalMs` | Clock-report interval, default/accepted 250; valid 100–5000. |

Keep the complete accepted profile when copying configuration: default constructor
values intentionally preserve older experiments and are not all the current
recommended combination. A 200 ms genuine-preroll comparison used advance 0,
startup drop 0 and retention true; the bridge rejects conflicting settings.
Preroll is an AAC experiment and is not a supported Opus-only combination.

The build targets the tested Portal's behavior. Many control responses are fixed
templates: changing frame rate, resolution or ISP fields in Protect does not
reconfigure the encoder. All three quality slots use the same source, and the
G5 Pro model identifier is a compatibility identity, not the physical device.

The normal AAC control template retains a legacy 16 kHz mono advertisement while
the actual AAC config/media are 48 kHz stereo. Prior metadata-only experiments
worsened live sync, so this mismatch is recorded rather than silently changed.
Actual decoder format must be read from codec headers, not assumed from templates.
