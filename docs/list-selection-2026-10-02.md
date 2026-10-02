# NPC listing and scoped selection

The list command now supports named registries, all/owner/tag/expression modes, type intersections and both page forms. Selection supports names/UUIDs, registry and range options, ray priority and nearby fallback, including virtual NPCs and redirect helpers.

## Listing and page actions

Player lists default to owned NPCs, with the existing owner/admin bypass rules. Console listing retains the native port's all-NPC default. The primary filter precedence is all, expression, tag, owner, then default; the type filter intersects that result. Provided values are validated before listing, and expression filtering requires administrative permission.

Every expression receives its own candidate NPC scope and the calling player context. This corrects the original body's use of the selected NPC for every row. Candidate snapshots and identity checks tolerate expression callbacks that remove or replace NPCs. Malformed Molang is reported at compile time rather than silently returning an empty result; invalid expression values remain false in boolean contexts. Unknown engine prefixes are rejected.

List rows and page buttons use native components. Quoted query values, operators, backslashes, legacy-looking formatting text and literal `$page` remain command data. Page links replace the effective page without accumulating flags. Labels/tooltips use translation keys. Quoted command arguments now decode escaped delimiters/backslashes so generated commands round-trip through the same parser.

Teleport, summon and remove actions carry their named registry. Global ID/UUID overrides also resolve in an explicitly supplied registry. The remove command accepts that registry directly, preventing a named-registry row from acting on a default-registry NPC with the same identifier.

## Selection

Explicit targets use the shared name/UUID/ID resolver and duplicate-name prompt; the existing `--name` form forces name interpretation, including numeric names. Prompt completion rechecks permission, candidate identity and registry availability. Selections retain logical registry identity alongside the NPC UUID, preserving normal reload behavior while avoiding cross-registry UUID collisions.

Implicit selection uses the command source's native position/rotation, a 15-block default range and native bounding-box ray tests, followed by nearest nearby candidates. The scan considers loaded entities and registered virtual NPCs rather than walking a range-sized grid of empty sections. Player-hidden or unpaired virtual targets are excluded. Redirect chains resolve into the requested registry and cycles are rejected. Explicit IDs remain usable beyond the nearby range.

`npc.selection.message` retains the original configurable message and supports an empty value to suppress feedback. Already-selected checks compare the actual scoped NPC instead of only its integer ID.

## Validation

The dedicated [runtime fixture](../tools/selection-runtime-audit/README.md) passes 126 checks on the initial completed 21.1.248 run. It includes real component-action execution and a million-block range input without chunk-grid scanning. Physical-client UI drawing remains separate acceptance.

The first fixture used an unavailable redirect setter and inspected only the last chat line; it was corrected to use the public trait constructor and complete command output. Native tests then reproduced wrong-registry removal and malformed Molang being treated as a zero-valued program. The implementation uses proper registry resolution and the expression library's parse-error handler.

Final default-version, regression, parser-test, build, source checkpoint and archive results are recorded in `artifacts/selection-validation-summary.json` and the root walkthrough. Interactions remains a separate mod/JAR. The remaining command groups and data/integration/client acceptance stay on [the current checklist](remaining-citizens-work-2026-09-26.md).

Both NeoForge 21.1.233 and 21.1.248 pass 126 dedicated checks. Related suites pass 305 creation, 302 relocation, 62 removal, 49 command-location and 80 text-editor checks, plus 107 NPC assertions and 223 general probes. All 100 Interactions tests and 232 ordinary tests pass, and the clean default-version build succeeds. Release/source jars exclude audit code, Interactions entry contents remain unchanged, and the original Citizens save hash is unchanged. No deployment or physical-client acceptance is claimed.
