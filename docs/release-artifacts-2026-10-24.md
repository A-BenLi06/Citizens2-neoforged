# Public release artifact receipt

Prepared 2026-10-24T12:34:35+08:00 (UTC+08:00).

Repository: `A-BenLi06/Citizens2-neoforged` (public).
Release tag: `v2.0.43-neoforge-20261024`.
Release notes: [2026-10-24 snapshot](release-2026-10-24.md).

Public asset staging: `artifacts/github-release-2026-10-24/`.
Combined ZIP: `citizens-neoforge-deployment-2026-10-24.zip`.
Size: 38,671,513 bytes.
SHA-256: `1cb44a7306c364c21cd810ef984097697e9f32749739adcf999370c5f15481c9`.

The public ZIP includes the four runtime mods Citizens, Interactions, Economy
and GriefLogger, their source archives, licenses, manifest, deployment/rollback
instructions and internal checksums. Individual runtime/source assets, manifest,
instructions and an external checksum list are staged alongside it.

The local five-mod deployment candidate remains intact. Its modified Paradigm
binary and private source are excluded from public assets because the actual
upstream license is CC BY-NC-ND 4.0. No private five-mod ZIP is uploaded.
All four runtime hashes and all public ZIP entries/internal checksums pass
read-only verification. No gameplay tests or server starts were added.

The release is published as a snapshot prerelease against the release-preparation
commit on `neoforge-port`. Source gameplay remains at the pinned `d0a7815`
checkpoint. GitHub generates the repository source archives from the release tag;
the separate component archives supply the external provider source checkpoints.
