# Security and privacy boundary

This is a development prototype for a trusted local network, not a hardened
camera appliance. Public source does not make its deployment files public.

- RTSP on TCP port 8556 is **unauthenticated and unencrypted**, listens on all
  interfaces, and can expose live camera/microphone data to reachable clients.
  Keep it on a restricted trusted LAN. No firewall is configured by this app.
- Native control uses mutual TLS with the local camera identity. Recorder leaf
  certificate SHA-256 pins are checked. HTTPS snapshot/clock requests use the
  same pinned trust. Certificate rotation requires deliberate reprovisioning.
- Native media on TCP port 7550 is unencrypted. Only the configured recorder host
  and expected media port are accepted for pushes; snapshot destinations must be
  HTTPS on that host. These constraints are not a substitute for LAN isolation.
- The manifest is debuggable for authorized ADB `run-as` provisioning. The generated
  APK uses a local development signing key. Do not describe it as a release-hardened
  production build. Protect ADB access and keep identity/signing files private.
- The app does not write recordings. Protect does. Logs contain operational
  metadata, and some exception diagnostics can include addresses; review and
  redact logs before sharing them. The exception filter is not a complete DLP tool.
- Published templates and examples contain neutral values/placeholders. Never
  commit `protect.json`, identity files, signing keys, tokens, captures, or logs.
  `.gitignore` helps with accidents; it cannot prevent force-adding a secret.

## Reporting a problem

Do not include credentials, camera IDs, recorder pins, home footage, or full
unredacted logs in public issues. Reproduce with synthetic media where possible.
If a credential is published, revoke/rotate it first: deleting a file in a later
commit does not erase it from history. No private security reporting channel is
configured by this repository; do not send private details to a public issue.
