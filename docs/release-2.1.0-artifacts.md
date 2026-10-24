# 2.1.0 release artifact receipt

Prepared 2026-10-24T17:19:33+08:00 (UTC+08:00).
Source checkpoint: `a25ff03c485dbee6456509d69dd65a7aa9ad5308`.
Target: Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21.

| Runtime artifact | Bytes | SHA-256 |
|---|---:|---|
| citizens-2.1.0.jar | 4,102,089 | adb97390851888b2efe1a166edc75d87ccc3e4d184159de73437b96ace74f445 |
| yuuniverse-interactions-2.1.0.jar | 204,124 | 035a7cc1aecb62f510da7e16a24243990fc5fab7debbe21425df23f2d420cecd |

Local staging: `artifacts/github-release-2.1.0/`.
Combined ZIP: `citizens-interactions-2.1.0-neoforge.zip`, 5,311,436 bytes.
ZIP SHA-256: `467800e4a7c9847d42c462c75c1f64228d126c83c1305e33ebf8927a98986cbb`.

Nine assets are prepared: two runtime JARs, two source archives, the existing
repository license, deployment notes, manifest, combined ZIP and checksum list.
Economy, GriefLogger and private Paradigm files are excluded.

Fresh isolated `assemble dialogueJar` succeeds without tests. Both runtime
versions resolve to 2.1.0. All 1,024 Citizens and 74 Interactions production
classes match the latest accepted snapshot byte-for-byte. The only differing
runtime archive entries are Citizens' manifest/mod descriptor and Interactions'
manifest. Namespace separation and fixture exclusions pass. ZIP CRCs and every
internal checksum pass read-only verification. Existing gameplay evidence and
physical-client limits are retained in the [release notes](release-2.1.0.md).
