# Portal Protect Camera

Experimental Android camera source with native UniFi Protect transport and a
direct RTSP endpoint. Current implementation: H.264 video, AAC recording audio,
and optional Opus live audio. Source-only baseline; deployment credentials and
recordings are deliberately excluded. Build with `ANDROID_HOME` set, then run
`bash app/build.sh`. Full setup and implementation documentation follows in a
separate commit.

Protocol templates include MIT-licensed third-party material; see
`app/assets/THIRD-PARTY-LICENSE.txt`.
