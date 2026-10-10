# Record command usage after payment succeeds

A per-command economy withdrawal can be rejected even when the balance check
succeeds. Previously, `CommandTrait` recorded cooldowns, use counts and sequence
position before attempting that withdrawal. A rejected payment could consume a
one-time command or skip it on the next sequential click without executing it.

Admission now checks permissions and limits without changing usage. Failed payment
leaves existing history intact and does not insert an empty player record. After
successful payment, the current player record receives the cooldown timestamps,
personal/global use counts and sequence position. Resolving the current record
after payment avoids overwriting history created or cleared by provider callbacks.
Expired cooldown cleanup remains part of the existing save pruning path.

An in-flight guard covers each NPC's encoded command key during per-command
admission, payment and scheduling. A provider callback cannot charge that command
again for either player before the first payment finishes. The guard is released
on every exit and shares the scope already used by command cooldown/use counters;
it does not persist or combine different NPCs' command state.

Sequence selection is read-only until payment succeeds, including attempts to wrap
or switch hands. CYCLE now records its successful command ID and rotates across
players; previously its shared position never advanced. SEQUENTIAL keeps separate
player positions. Both modes wrap when the previous highest ID has been removed.

The native creation fixture uses ordinary/packet NPCs, two connected mock players,
a rejecting economy provider, real scheduled commands and persistence snapshots.
It checks failed withdrawal preservation, a successful retry, same-command payment
reentry, exactly one paid reward, subsequent sequence progression, rejected wrap
and other-hand payment, shared/per-player rotation and highest-ID deletion.
The original implementation fails
`failed_command_payment_preserves_saved_usage_false` in
`artifacts/command-payment-usage-before.log`.

The fixed implementation passes 586 creation checks on each of NeoForge 21.1.233
and 21.1.248 in `artifacts/command-payment-usage-native-233.log` and
`artifacts/command-payment-usage-native-248.log`.
A clean ordinary test/build passes all 725 tests with no failures, errors or skips
in `artifacts/command-payment-usage-release-build.log`. Release binary/source JARs
contain no audit probes. Citizens contains 859 Citizens classes and no Interactions
classes; the independent Interactions JAR contains 70 Interactions classes and no
Citizens classes. Source, log and archive hashes are recorded in
`artifacts/command-payment-usage-validation.json`.

This change defines successful per-command payment as the point where usage is
recorded. Global click-cost policy, dispatch-event recursion, delayed-command
retirement, scheduling/command failures after payment and arbitrary external
provider side effects remain separate concerns. Physical-client/modpack acceptance
has not been performed.
