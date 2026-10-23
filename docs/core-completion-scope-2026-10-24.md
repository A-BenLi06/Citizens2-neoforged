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

Current core review starts with template event persistence. Applied event
subscriptions currently exist only in memory. A restart must restore the event
commands without rerunning template-application commands or destructive trait
replacement. Definition edits should take effect through explicit reapplication,
preserving the established file-reload contract.

The interrupted GriefLogger lookup experiment is superseded draft work. Its
already-started isolated reproduction and source build may finish safely; no
fixed provider fixture, adoption or deployment is required. Keep its source,
tools and observations recoverable under ignored artifacts.

Core completion is not yet established. Historical command-name coverage and
bounded tests do not prove all gameplay or physical-client behavior.
