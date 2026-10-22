# Shared commerce progress with offline contributors

Recorded 2026-10-22, UTC+08:00. Extends the
[current-actor financial recovery check](commerce-financial-backup-recovery-2026-10-22.md).

## Correctness boundary

An older Economy database can retain the online party member's settlement while
losing an offline member's later settlement. Unrelated trades can regain the same
or a higher global sequence. The current-actor check alone previously allowed
reconciliation of already matching shared progress against that inconsistent
financial backup. An actual two-member native party reproduces this failure on
Citizens `66d52d3` with the new provider inspection API available.

Citizens now verifies retained WAITING/CONFIRMED/APPLIED evidence from every
contributor in the relevant binding/team/cycle. Replay validates scopes belonging
to the current actor before retaining more history. New attribution also validates
the current native scope, including for an actor with no earlier contribution.
Projection checks all contributors before either an already-matching early return
or a native progress write. Review resolution validates its destination scope.
Original teams, cycles and immutable attribution remain authoritative after party
changes; inspection does not redirect history to a current party.

Economy 0.5.7-SNAPSHOT, Commerce capability 4, adds supported read-only
`inspectOperation(owner, operation)` for online or offline account owners. It
returns journal identity/high-water, original owner-scoped history metadata and
receipt, and the current inventory outcome. Citizens compares exact request,
sequence, trade quantity and receipt payload plus the original delivery policy.
Only WAITING may remain unsettled. Observations are reused only within one
synchronous validation call, never across admissions or native callbacks.

Capability 3 remains supported while each required contributor is online. An
offline contribution requires capability 4; Citizens reports that requirement
instead of treating absent session access as settlement proof. Trading, menu and
administrative actor authentication retain their existing online-session checks.
No foreign implementation is bundled into Citizens or Interactions.

## Native acceptance

The extended `tools/run-financial-backup-audit.ps1 -Scope shared -EconomyJar <jar>`
uses a fresh loopback fixture with actual FTB party creation, invite/join and two
distinct connected ServerPlayers. The first member trades, a native SQLite backup
is taken, then the second member trades and disconnects. Both immutable APPLIED
records and shared native progress are saved. On restart only the first member
connects. The older ledger still contains that member's original settlement.

Stopped restore cases cover lower/equal/higher global sequence and a matching
ledger. Missing offline settlement blocks reconciliation and new consumer credit
without rewriting either original record or changing existing task, inventory or
money state. Provider-owned trades still execute exactly once. With the matching
ledger, reconciliation and a further native trade advance shared progress exactly
once while the other member stays offline. Provider-balanced ledger checks and
read-only SQLite integrity checks remain independent evidence.

The actor-only cases also run against the retained 0.5.6 provider to check
capability-3 compatibility. Selected XMod/Rhino/party/reward/commerce checks run
against 0.5.7. Ordinary Citizens tests build both NeoForge targets; Interactions
has its separate suite. Exact final counts, identities, sources, provider artifacts,
logs and release archive hashes are in
[the validation manifest](commerce-shared-backup-validation-2026-10-22.json).

## Remaining work

This verifies commerce evidence admission and projection. It does not yet fence
native item/command/monetary reward admission after an inconsistent financial
restore. Monetary PAID claims need their own read-only settlement contract.
Large-history throughput remains unmeasured, and deleting or rolling back all
copies of both financial and consumer evidence cannot be detected here. No new
process-kill, power-loss, physical-client or production deployment acceptance is
claimed. Original legacy quest/item sources and physical ordinary/VR/proxy checks
remain open; the user will test the pinned ordinary-client fixture later.
Reconciliation with every contributor offline, and replay by a newly joined
member with no retained contribution, still need explicit acceptance and any
necessary recovery implementation.
