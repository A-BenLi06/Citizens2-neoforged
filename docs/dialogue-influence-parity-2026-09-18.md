# Dialogue influence parity

Updated: 2026-09-19 10:14:38 +08:00 (UTC+08:00). Implementation base: `7b582a9`. Reference: the installed Interactions 2.14.1 jar, SHA-256 `ce4824a2bb4317eb9acd40cd4388eebedfab521d96310bf74f2d26f0ac85293e`. Target: Minecraft 1.21.1 / NeoForge 21.1.248, JDK 21.

## Reference and data model

Original `ActionUtils`, `InteractionsAPI`, `PlayerDataManager`, `PlayerData`, `PlayersConfigsManager`, `MainCommand`, `ExpansionInteractions` and `ConversationConfigsManager` establish the following rules:

- Influence belongs to a player UUID and the exact conversation filename without `.yml`. It does not belong to a displayed NPC name, Citizens numeric ID or individual dialogue node. Keys are case-sensitive, and missing values return zero.
- Player YAML stores a list such as `influence: ['faction;25', 'other;-7']` beside saved dialogues and cooldowns. Records for removed conversations remain meaningful stored data.
- `influence: set;N` accepts any signed integer. `add;N` and `remove;N` require positive amounts. Removal can produce a negative value; there is no 0–100 bound. Writes require an existing conversation.
- Administration is `/interactions influence <set|add|remove> <online-player> <conversation> <amount>`, guarded by `interactions.admin`.
- `%interactions_influence_<conversation>%` exposes the value. The original condition implementation also supports numeric comparisons.

Bytecode evidence is retained locally as `artifacts/legacy-influence.txt`, `legacy-influence-context.txt`, `legacy-influence-configs.txt`, `legacy-influence-conditions.txt` and `legacy-action-utils.txt`.

## Implementation

`Influence` is a public native service with `get`, `set`, `add`, `remove` and `change` methods; the controller exposes its instance through `InteractionsMod.influence()`. Administration, actions and preflight share its operation rules. `ProgressStore` reads and writes the legacy list, atomically replaces complete YAML files through the existing dirty-record lifecycle, preserves other fields and retains values for absent conversations. UUID ownership survives renames and fresh store loads.

Malformed list shapes, malformed/non-integer entries, out-of-range values and duplicate keys mark the player record unreadable. Reads and writes fail instead of turning corrupt data into zero or overwriting it. The original file remains untouched. Integer overflow/underflow is rejected instead of reproducing Java integer wraparound from the original plugin.

Actions carry the conversation ID explicitly through initial, completion, option, interruption and delayed batches. The old context-free overloads still work for other actions; an influence action without its context fails preflight. NPC display text is never used as a storage key.

Preflight projects native influence operations in sequence, including influence placeholders in later action arguments. It checks cumulative overflow before item payments or earlier influence changes. A resumed tail is projected again from current player data. This does not simulate arbitrary console commands, external provider mutations or future inventory gains; earlier successful side effects remain committed if a later delayed segment fails, consistent with the existing batch model.

Text, JSON component string values, inline/ordinary/selectable option labels, hover text, typed-option matching and actions resolve influence from the same store. Unknown placeholders remain unchanged in display text and cannot satisfy unknown requirements. Saved-dialogue placeholders also use this text path. Requirements now support `>`, `>=`, `<` and `<=` with exact decimal comparison, including placeholders on either side; nonnumeric/non-finite values fail closed. Operators inside percent-delimited placeholder arguments are not treated as comparisons. Existing case-insensitive equality behavior is retained; the original's other string operators, `or` expressions and broader variable/provider system remain separate gaps.

The native command tree registers `interactions.admin` with NeoForge and uses the existing permission bridge, including explicit backend denial and source elevation rules. It accepts the original unquoted Unicode filenames plus quoted filenames containing spaces. A standard vanilla greedy-string wire argument is parsed into the filename and integer, so no custom client argument type is required. Invalid/trailing arguments, offline targets, unknown conversations and overflow throw command exceptions without changing data. This matters because Brigadier treats a normal return value of zero as successful execution: the dialogue engine must receive a failed callback so later rewards stop and progress is not saved. Action preflight validates this owned command's grammar and target; runtime bounds are checked at execution, since earlier native actions may change the value before the command. General commands are not projected like native influence actions. `/interactions influence get <online-player> <conversation>` is a read-only extension. Messages use translatable keys with English fallbacks. This does not yet restore the original configurable administration-message keys or Bukkit binary API.

## Validation

Final sequential validation passes **61 influence checks**, **84 scheduled-action checks**, **91 native-action checks**, **127 display checks**, **66 dialogue unit tests**, **107 NPC assertions**, **223 general runtime probes** and **166 ordinary tests**. Unit failures/errors/skips are zero. The normal build succeeds and release/source jars contain no runtime probes. The final log markers and hashes are in `artifacts/influence-validation-summary.json`. Interactions jar SHA-256: `e6acb488293324565db9e812247827dc17ad68430d582c107bb3681d113a094b`. Unrelated dedicated entity/pickup/movement/text/provider/removal suites retain their prior evidence and were not rerun for this batch. The isolated display fixture requires a separate `[INFLUENCEAUDIT] COMPLETE` marker in addition to the existing three markers. It exercises native API values, actual command dispatch, a scoped NeoForge permission-handler fixture, packets/components, real sessions, saved data and server-tick-delayed actions. The permission fixture verifies handler integration; it is not a fresh Paradigm installation test.

Runtime development caught the standard string argument's Unicode restriction. Independent review found and corrected the command-success callback defect; additional batch/session tests verify reward abortion, unsaved failure progress and acceptance after an earlier native reset. Fixture corrections also aligned permission injection with NeoForge's registered-node ownership and respected existing hidden-row/next-control semantics. No production behavior was altered to satisfy those fixture assumptions.

## Remaining scope

Original action recognition reaches **17/18**, with `send_to_server` still missing. This count is not an overall parity percentage. Unknown-world fallback and explicit aliases, the remaining condition grammar, dialogue authoring/configuration and incoming-chat behavior, quest/saved-item fidelity, group provider capabilities, Citizens API/parameter behavior, Sentinel and physical-client/full-modpack acceptance remain open. CmdCam retains camera playback and Yuuniverse Economy economic APIs. This batch changes no production files and performs no deployment.
