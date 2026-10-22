# Validate delivery outcomes after native completion callbacks

Recorded 2026-10-22, UTC+08:00. Extends
[delivery outcome recovery](ftb-delivery-recovery-2026-10-22.md) and
[evidence target validation](evidence-target-mutation-2026-10-22.md).

Native reproductions on `0c0c46d` expose three false CONFIRMED outcomes in the
Citizens submission adapter. A completion callback can remove the native party,
replace the ItemTask with an identical public ID/definition, or retire the actual
player session. The native method still returns after consumption, and the
adapter previously read its held objects as if they were authoritative. Exact
before-fix task/team/actor/entry identities and failed assertions are retained.

After native submission returns, the adapter now verifies that the original
player object is still the active online session. It resolves the admitted team
UUID and task ID through current native managers and checks file, TeamData, task
and quest identity plus the admitted serialized definition revision. It then
reads the outcome from those fresh objects. Deletion, replacement, definition
changes or session retirement enter the existing durable REVIEW transition with
the original component-sensitive input and attribution. No item consumption,
progress, reward or arbitrary callback effect is undone or replayed.

Lookup uses the original admitted team. A player can legitimately leave a party
which continues to exist; confirmation still belongs to the original native
delivery. Requiring current membership would incorrectly redirect or reject that
outcome. Actual reward claims can also advance a repeat quest's cycle and reset
its progress before the submission returns. Existing native cycle retention
semantics continue to accept that outcome when identity and definition remain
valid.

The disconnect reproduction demonstrates why the active-session check matters.
FTB advances progress before the reduced stack is installed. Disconnecting inside
completion saves the old five-paper inventory; the retired object's native tail
then consumes its in-memory stack. A reconnect loads the independently saved
five paper. REVIEW blocks another consumption without pretending that the
retired object's final inventory was saved. The journal retains uncertainty for
authenticated operator review; confirmation is not a cross-file transaction.

The opt-in mutation suite uses eight independent native quests/players in a fresh
loopback fixture: removed party, identical task replacement, retired/reconnected
session, serialized definition edit, task deletion, valid party leave after
ownership transfer, actual native repeat reward/reset and unchanged completion.
The original definition-edit/deletion cases already entered REVIEW through the
journal's consistency checks. They remain regression controls. A draft party-
leave control omitted the required native ownership transfer and is retained as
a fixture diagnostic; no production workaround was added.

The audit init and crash runner accept explicit fixture/build/cache locations so
fresh runs preserve historical evidence. Paths stay under task artifacts, and
the runner still verifies a fresh run token, exact argument file and Java process
creation time before terminating only its isolated fixture.

Final validation passes 61 mutation checks, 17 normal delivery checks, 104 selected
integration checks and six actual JVM kills with 18 boundary/40 restart checks:
240 native assertions. Both ordinary NeoForge 21.1.248/21.1.233 builds pass 775
tests each; separate Interactions passes 100. All six release archives preserve
mod ownership, exclude probes/test mixins/foreign implementations and match final
source and tested native class bytes. Five stopped fixtures pass exclusive
world/database access checks; immutable read-only SQLite inspection confirms
integrity, balanced postings and original settlement identity. Exact sources,
providers, packages, journals, native saves, process identities and results are in
[the validation manifest](delivery-callback-validation-2026-10-22.json).

Broader storage/retention/load and large-history throughput, callbacks that change
and restore definitions before returning, remaining recovery boundaries, original
migration provenance and physical ordinary/VR/proxy acceptance remain open. The
deferred BenLi06 fixture stays pinned and stopped. Production activation is not
part of this work.
