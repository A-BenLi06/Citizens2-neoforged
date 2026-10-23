# Paradigm hologram reload failure on Windows

Observed 2026-10-23, UTC+08:00, with pinned unmodified Paradigm 2.5.0,
Java 21.0.11 and Windows 10. This is an open provider defect.

Follow-up 2026-10-24, UTC+08:00: a [private owner repair](paradigm-hologram-repair-2026-10-23.md)
passes 38 standalone and 44 full-pack native/restart checks with the same probe
bytecode used to reproduce the original failure. It changes only the owning
loader method and one JAR entry, closes the reader before persistence and
preserves actual write-failure safety. This acceptance applies to that private
candidate; the unmodified provider described below remains defective. No modified
provider is distributed or activated in production or the deferred client fixture.

A fresh combined-server start creates `config/paradigm/holograms.json` with
`enabled: true`. The next process logs `Failed to save holograms.json`, caused by
`AccessDeniedException` replacing the existing file with `holograms.json.tmp`.
Paradigm then disables holograms for safety. Other initialization and Citizens
placeholder registration continue; that does not establish working holograms.

`HologramStore.load()` calls `write(loaded)` inside the try-with-resources block
that still owns the reader. Windows denies replacement while that reader is
open. Installed bytecode and previously reviewed upstream source show the same
ordering. The original file remains writable and its contents remain unchanged.

The standalone [reproduction](../tools/paradigm-file-audit/ParadigmHologramFileAudit.java)
loads the actual provider class and dependencies. Fresh construction enables
holograms. Another construction using that file and reload on the original store
both disable them. Original bytes remain exact. Replacing that same file after
the reader has closed succeeds on the same filesystem with those same bytes.

The executable class, output and installed disassembly are retained in
`artifacts/integrated-paradigm-file-audit-classes`,
`artifacts/integrated-paradigm-file-audit.log` and
`artifacts/integrated-paradigm-hologramstore-bytecode.txt`. The combined provider
validation record pins them and the unmodified provider hash.

The owner-level correction is to close the reader after parsing, before
normalization/persistence replaces the file. Acceptance must include
fresh/existing/reload cases, corrupt-config safety, original definition
preservation and native restart. Deleting the file or suppressing the error
would not repair this behavior.

No modified Paradigm provider is distributed. Its existing
[provider/API release constraint](paradigm-inbound-api-requirements-2026-10-22.md)
still applies; reviewed upstream 2.5.2 also retains this load ordering. No upstream
message or issue was sent. This remains a release gate alongside supported
inbound evaluation and physical-client checks.
