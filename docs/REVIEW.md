# Baseline review

The first public commit preserves the current implementation, with only privacy
and portability substitutions required for publication. This follow-up documents
behavior and adds comments; it does not deploy runtime changes to a device.

## Findings and disposition

1. Local addresses, local build paths, private diagnostics, and operational
   identities did not belong in public history. The import is source-only,
   templates use neutral network/timezone values, the build uses SDK environment
   variables, and publication was scanned before the first push.
2. Existing README text described superseded audio experiments. Replaced with
   current dual AAC/Opus behavior, the Opus-only recording limitation, and a clear
   boundary between observed phone results and unobserved codec negotiation.
3. The default timing values do not all match the accepted tested profile. The
   placeholder config specifies every accepted value; code comments preserve
   legacy behavior rather than changing timing during a documentation review.
4. All native quality slots share one encode. This is now explicit, along with
   requested versus delivered frame rate and on-demand snapshot cost.
5. Private identity creation/adoption is outside this source snapshot. Setup
   documentation says so and does not pretend placeholders create an identity.
6. RTSP lacks authentication/encryption; media pushes are unencrypted; the APK
   is debuggable. Documented as actual current limits, without claiming the app
   is hardened or silently changing the known working deployment.
7. Control templates acknowledge some unimplemented settings and retain a legacy
   AAC metadata mismatch. Documented rather than changed as part of the import.
8. Test scripts depended on local folders and real-camera input. Paths are now
   repository-relative; synthetic media is the default, with hardware input an
   explicit opt-in. No captures or test artifacts are stored in Git.

## Follow-up engineering questions

- Validate private config fields and dynamic header values more comprehensively.
- Test microphone reopen/input-buffer loss against the synthetic sample clock;
  the current counter advances only for successfully submitted AAC input.
- Investigate receive/render buffering with actual phone session diagnostics.
- Harden RTSP parsing, socket timeouts, and client admission before broader use.
- Review lifecycle races during encoder shutdown and native-mode reconnects.
- Reconcile capability/control templates with implemented settings through
  controlled compatibility trials, not unverified advertisement changes.

These are engineering follow-ups, not claims that the publication privacy audit
establishes runtime correctness or security. No functional Android fixes were
bundled into the documentation/comment commit.
