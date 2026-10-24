# Citizens 1.21.1 completion scope

2026-10-24, UTC+08:00. This scope follows the user's latest instruction and
supersedes the individual third-party implementation and acceptance gates in
older planning documents.

Prioritize Citizens itself on Minecraft 1.21.1: NPC creation and persistence,
traits, navigation, commands, templates, behavior execution, native events,
networking and client-facing behavior. Third-party support requires stable
native extension interfaces. Preserve existing integrations; individually
implementing or fully accepting every external mod is no longer required.

Existing provider acceptance reports remain evidence for their stated inputs.
Missing legacy quest mappings and ItemEdit definitions do not block core work.
The ordinary-client acceptance run remains explicitly deferred by the user;
do not restart its fixture or treat it as passed. Production activation and
release publication have not occurred as part of this scope change.

Template event persistence has now passed 836 ordinary tests per supported target
and 126 native seed/restart checks across NeoForge 21.1.233 and 21.1.248.
Applied command subscriptions restore from the NPC
snapshot without replaying template-application commands or destructive trait
replacement. Definition edits still take effect through explicit reapplication.
See [the focused acceptance record](template-event-persistence-2026-10-24.md).

Continue the core review against remaining Citizens-owned behavior and API
contracts; this focused fix does not establish overall completion.

The [navigation command follow-up](navigation-commands-2026-10-24.md) validates
whole edits before mutation, rejects non-finite movement inputs, restores native
movement for actively navigating packet roots and requests the correct vanilla
target block. Both supported targets pass 842 ordinary tests and 229 native
navigation checks each. Fresh label/mount regressions pass another 563 checks;
independent stopped YAML/NBT, production-class and package verification passes.

The next demonstrated core persistence gap is in `CitizensNavigator.save/load`:
command-edited attack range, attack delay and path distance margin are absent
from serialization. Verify and repair their save/restart behavior next. Physical
ordinary-client acceptance remains deferred.

The interrupted GriefLogger lookup experiment is superseded draft work. Its
already-started original-provider reproduction stopped normally and the draft
source build succeeded. The private source checkpoint is `445e764`; tools are
preserved under `artifacts/logging-filter-superseded-draft/tools`. There was no
fixed-provider native acceptance, adoption or deployment.

Core completion is not yet established. Historical command-name coverage and
bounded tests do not prove all gameplay or physical-client behavior.
