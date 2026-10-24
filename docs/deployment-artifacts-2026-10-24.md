# Deployment artifact receipt

Prepared 2026-10-24T12:13:07+08:00 (UTC+08:00).

- Directory: `artifacts/deployment-candidate-2026-10-24/`
- ZIP: `artifacts/deployment-candidate-2026-10-24.zip`
- ZIP size: 40,793,773 bytes
- ZIP SHA-256: `c98a0ace609ae9a075265eafa68c504f3095c227ad948eeaa96b727a6bef6f0c`
- Deployment and rollback: [candidate instructions](deployment-candidate-2026-10-24.md)
- Exact source checkpoints, inputs and JAR hashes: [release specification](deployment-candidate-2026-10-24.json)

The ZIP contains five replacement mod JARs, source archives, a 64-provider
reference inventory, build log, acceptance reports, deployment instructions and
file checksums. It is private local output under ignored artifacts; no live
server, distribution directory or remote release was changed.

Fresh `assemble dialogueJar` succeeded on Java 21.0.11 / NeoForge 21.1.248.
Every Citizens and Interactions archive entry matches the latest accepted
ordinary build's entry contents. Economy 0.5.9, GriefLogger .10 and the private
Paradigm hologram repair match their adopted hashes. All five descriptors,
versions, CRCs and package boundaries were inspected. Every packaged checksum
and ZIP entry was independently reread successfully. No new test suites or
Minecraft processes were run.

The initial packaging preflight conservatively rejected Paradigm's legitimate
production `modules/audit` classes before creating output. The rule now targets
the actual Citizens fixture namespace and runtime/test classes; production audit
services remain intact. The candidate contains no fixture-augmented Citizens or
Interactions archives. Prior physical-client acceptance remains deferred and
the remaining Citizens scalar-default review is recorded in the linked reports.
