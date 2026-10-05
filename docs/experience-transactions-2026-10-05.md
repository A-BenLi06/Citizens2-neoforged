# Checked experience-level transactions

ExperienceAction now validates configured levels and repeat counts before multiplying,
using long arithmetic and rejecting totals outside the native integer range. Negative
costs/repeats cannot become rewards, and overflow cannot wrap into a free charge or a
negative grant. Zero amounts remain no-ops. Invalid costs have zero affordable repeats;
a free cost retains the no-limit sentinel.

Both execution and refund check the current level range before calling Minecraft's
giveExperienceLevels. This catches changes since isPossible and prevents insufficient
charges or overflowing grants/refunds from invoking vanilla's negative-level reset.
Normal level changes preserve the fractional progress and total-experience fields. This
is level-based pricing, not a conversion into raw XP points.

NeoForge's native level-change event can cancel, rewrite or throw during an adjustment.
The action now verifies that the exact configured level change occurred without erasing
fractional/total XP. An operation whose observed result differs, including normal cancellation or amount rewriting, is rejected and its local XP fields
are restored through the non-event-firing level setter, which also requests client sync.
This does not force a charge through a canceled event. Arbitrary listener side effects
outside those XP fields cannot be undone. Providers that intentionally rewrite shop XP
amounts need an explicit integration policy rather than silently changing a fixed price.

## Evidence and limits

Three arithmetic tests cover multiplication, bounds and repeat calculations. The
[native fixture](../tools/experience-runtime-audit/README.md) passes 49 checks on each of
NeoForge 21.1.233 and 21.1.248, including late affordability changes, refunds, native
shift purchases and failed-result compensation. The original implementation fails
negative_cost_rejected. A second control with arithmetic checks but no outcome verification
fails native_event_failure_rejected_CANCEL. MoneyAction already had non-finite/negative and numeric-range
checks; its arithmetic tests were also run without changing that implementation.

Settlement regression passes 31 checks. All 343 ordinary tests and 100 Interactions tests
pass without failures/errors/skips, followed by a successful clean test/build. Release
archives exclude fixtures. All 78 Interactions entry names/contents remain unchanged,
including its versioned manifest; ZIP container hashes differ. The original Citizens save
retains SHA-256 `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Extreme-level fixture values are synthetic boundary probes, not support claims for
normal gameplay at Integer.MAX_VALUE levels. A refund that becomes unaffordable or
out-of-range is reported rather than clamped; arbitrary changes by other providers or
callbacks still require separate reconciliation. Physical-client/provider acceptance
and full reload transactionality remain open. Local evidence is summarized in
`artifacts/experience-validation-summary.json`.

Interactions remains an independent NeoForge JAR with dependency Interactions → Citizens.
