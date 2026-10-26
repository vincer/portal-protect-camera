# Public source audit

Publication was prepared from an explicit source allowlist in a new repository,
not by committing the surrounding development workspace. The initial repository
contains Android source, generic protocol templates, test source, build script,
ignore rules, and license notices. No pre-existing workspace history was imported.

Before the initial push, every candidate file was read/reviewed, checked for
personal paths/contact details, local network addresses, device identifiers,
private-key markers and common provider tokens, and compared against string
values in the real private deployment configuration without logging those values.
A checksum-verified official Gitleaks binary scanned candidate files and complete
initial commit history with redacted diagnostics; no leaks were found.

Private identities, passwords, tokens, certificate pins, deployment configs,
recordings, screenshots, decoded audio, packet captures, logs, checkpoints,
build outputs, signing keys, and reference-repository history were excluded.
The local recorder allowlist was replaced with validation of the explicitly
configured host; the SDK path became an environment variable. Generic template
network and timezone values were neutralized. These substitutions keep private
installation information out of the first commit.

Commits use the repository owner's public GitHub handle and GitHub noreply
address, not a private contact address. Required upstream MIT copyright notices
are retained: they are public attribution, not private deployment data.

The follow-up comments/docs and complete history are rescanned before pushing.
Both the full Android build and media tests are checked on a separate build copy.
Synthetic test media is generated temporarily and deleted. Audit results are a
point-in-time review of the published material, not a guarantee about future
commits or a runtime security certification. Review the complete staged diff and
rerun a secret scanner before publishing new configuration or diagnostic data.
