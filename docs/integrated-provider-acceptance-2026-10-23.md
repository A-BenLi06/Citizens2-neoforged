# Packaged provider acceptance

Recorded 2026-10-23, UTC+08:00. Target: Windows 10, Java 21.0.11,
Minecraft 1.21.1 and NeoForge 21.1.248. All four selected server processes pass
286 checks and independent saved-state verification. Full release remains open.

The fixture uses the 64 pinned top-level archives from the deferred
ordinary-client candidate, replacing only Citizens, Interactions, Economy and
GriefLogger with their accepted current artifacts. AutoModpack remains excluded,
as in that candidate. Actual runtime inventory resolves 121 IDs/versions,
including nested and Fabric-bridged providers.

Citizens/Interactions production classes and every original release archive entry
remain byte-identical. Separate audit source sets append 29 fixture-owned classes
to candidate copies. Those augmented archives are not release packages. The
deferred physical-client fixture and production remain untouched.

| Process | Checks | Result |
|---|---:|---|
| XMod, prepared commerce, commerce tasks, party rewards | 104 | Passed |
| Quest, delivery, crafting, item library and permissions | 137 | Passed |
| Logging, independent histories, world rollback and commerce | 37 | Passed |
| Logging/commerce restart | 8 | Passed |

The first process runs in `artifacts/integrated-provider-candidate-server`.
Its completed stopped world/config/SQL images seed the fresh
`artifacts/integrated-provider-final-server`. The latter inherits the first
process's evidence; it does not rerun those 104 checks. Later processes use the
same saved continuation world at `127.0.0.1:25641` with one selected probe per
process. Every completed process must exit zero and release native storage locks.

Two earlier quest attempts are preserved and excluded from accepted counts.
The historical standalone probe expected Economy to be absent. Installed Economy
instead correctly rejects the nonexistent currency and shop through
`IllegalArgumentException` and `ShopApiException/NOT_FOUND`. Corrected assertions
check unchanged existing bindings/claims/entries and the specific missing-shop
reason. A valid installed-provider menu preflight must succeed without changing
the menu. The final fixture's immutable provider links reduce duplicate disk use;
packaging replaces owned entries atomically without overwriting linked inputs.

The independent verifier compares packaged entries/classes and actual resolved
provider versions, process exits, complete saved SQLite table definitions/rows,
generated entity/material references, balanced ledgers and original financial
records. It also checks original quest journals, native team/player files,
delivered item components, stopped storage ownership, provider/source hashes and
single-use launch rejection. The [validation manifest](integrated-provider-validation-2026-10-23.json)
pins exact archives, probe classes, process results, captures, sources and frozen
executable references. No accepted native phase was replayed to fix recording.

All 229 original journal files, seven FTB team saves and four original player
saves remain byte-identical through the later processes. Original financial
transactions/entries/commerce journals survive exactly; all account projections
agree with their ledger sums and original player balances remain exact. System
funding accounts change through the new logged trade's recorded funding entries.
Currency definitions and creation times remain exact; their update timestamps
refresh on startup. The initial verifier's timestamp assumption and its failure
are retained separately.

Native saved NBT independently contains two delivered stone items with the
original delivery claim, and the restored chest at `(7,-60,3)` contains all seven
named paper items with their original components. The final receipt remains
unique and the actor's balance remains 980 minor units after world rollback and
restart. Four completed/inherited phase launches reject before Java starts and
preserve all 4,565 protected files. The 69 previously pinned deferred-client files
retain their hashes; the deferred client world also remains exclusively stopped.

This combined run also found and independently reproduced
[Paradigm's Windows hologram load/reload failure](paradigm-windows-hologram-loading-2026-10-23.md).
The unmodified 2.5.0 keeps its existing file reader open while replacing the
configuration, then disables holograms after `AccessDeniedException`. Successful
permissions and placeholder registration do not close this defect. Full logs
also retain third-party recipe/tag and mixin warnings and slow WorldEdit block
state enumeration; no clean startup or sustained performance claim is made.

No physical client, VR reach/hand interaction, camera/skin/proxy acceptance,
original quest mapping or missing ItemEdit definition is supplied by these
synthetic fixtures. Wider machine/task/world/financial crash and backup
combinations, retention/load, supported Paradigm inbound evaluation and the
stopped production release/rollback decision remain open. Existing ordinary test
results remain historical evidence for unchanged production sources; no new
ordinary suite run is claimed here. See the [audit workflow](../tools/integrated-runtime-audit/README.md).
