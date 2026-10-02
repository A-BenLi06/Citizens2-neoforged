# Waypoint provider lifecycle and delayed actions

This follow-up repairs concrete waypoint data and lifecycle defects in the Citizens NeoForge port. Interactions retains its own mod/JAR and is unchanged.

## Provider changes and persistence

An unknown provider name previously removed the active provider and its route before lookup failed. Replacement now constructs and validates the candidate before retiring the current provider. Factory/removal callbacks that install a newer provider win over the older request. Provider state is published before cleanup callbacks so recursive switching cannot erase the replacement.

Providers bind to an NPC through `onAttach` without starting movement. Spawn starts a provider once for the current entity; despawn, trait removal and reload retire its behavior, with no duplicate cleanup after a despawn. Explicitly disabling a provider persists an empty provider value, while unavailable provider names and their full data remain inert and survive fresh saves. Guided provider saves remove old points, and repeated loads do not append duplicates.

`MemoryDataKey.copy()` now recursively copies maps, lists and arrays. This prevents a retained provider snapshot from changing when a caller later edits a nested list; literal keys remain literal. A focused ordinary test verifies mutation isolation in both directions.

## Delays and navigation ownership

Each arrival captures one immutable trigger-array snapshot. Delayed continuations validate NPC/registry identity, the current trait/provider and route revision. Removal, provider switching, route edits, explicit later pause requests and superseding arrivals invalidate obsolete continuations. Despawn cancels a waiting sequence and releases its own temporary pause. A pre-existing pause stays in place; a later explicit pause is not undone when the delay expires. Immediate callbacks are checked between triggers, and command triggers stop after the NPC is removed or despawned.

Linear, guided and wander cleanup cancels only navigation associated with the provider's own request parameters. Completion callbacks are installed before native navigation-begin events. A canceled guided segment no longer advances the plan or fires an arrival action. Callback replacement routes retain their own parameters and do not inherit callbacks from the old waypoint route. Normal arrival, cached-route replay and both straight-line/provided-path preparation are covered by the fixture.

The `WaypointProvider` interface adds default attach, pause-revision and route-revision hooks. Native providers implement them. Custom providers must implement revisions to distinguish repeated explicit pause requests and in-place route changes. New configurable straight-line/provided-path `Navigator` overloads run configuration before callbacks in `CitizensNavigator`; legacy custom implementations retain a fallback that configures after starting and should override those overloads for the same preparation guarantees.

## Commands

Waypoint add/remove commands validate coordinates, world and index before allocating traits or editing the route. An unspawned NPC without a stored world uses the native command-source world unless a world is supplied. Invalid explicit indices never fall through into coordinate removal. Coordinate removal compares world and position, so an editor-created point with nonzero rotation can still be removed. Unknown flags are rejected. An unsuccessful provider selection retains the old route and editor.

## HPA comparison

The original `waypoints hpa` / `wp hpa` command constructs an `HPAGraph`, adds clusters, computes a path to the player and prints it to stdout. It does not select an NPC navigator or modify a waypoint provider. The inspected NeoForge navigator supports its existing Citizens/Minecraft/plugin strategies; none uses the HPA graph. The [upstream graph source](https://github.com/CitizensDev/CitizensAPI/blob/master/src/main/java/net/citizensnpcs/api/hpastar/HPAGraph.java) is retained as a hashed comparison artifact alongside the checked-in command source.

Those debugging aliases remain unported. Substituting ordinary A* behind their names would not reproduce the original operation. A native HPA graph port remains separate work if that experiment is retained; this follow-up does not claim it is implemented or use command-name counts as completion evidence.

## Validation

The [isolated native fixture](../tools/waypoint-lifecycle-runtime-audit/README.md) first reproduced route loss after selecting an unknown provider. Expanded checks cover lifecycle callbacks, persistence, all three native providers, world/virtual NPCs, delayed actions, pause ownership, successful/canceled navigation, cached paths and reentrant replacements. The cache fixture initially placed a path above unsupported air; native verification correctly rejected it. The corrected fixture places that route on the flat world's ground.

Final dedicated runs pass 127 checks each on NeoForge 21.1.248 and default 21.1.233. Related regressions pass 129 follow, 81 movement/list, 305 creation, 62 removal and 216 mounted-transfer checks. General validation passes 107 NPC assertions and 223 probes. All 100 Interactions tests and 233 ordinary tests pass, including the new nested-copy test, and clean test/build succeeds. Tested production/fixture checkpoints match. Release/source archives exclude audit fixtures, and the separate Interactions archive has unchanged entry names/content.

Evidence, tested-source checkpoints, diagnostics, reports and archive comparisons are consolidated in `artifacts/waypoint-lifecycle-validation-summary.json`. Physical-client editor/marker presentation, arbitrary custom-provider contracts, live services/data and combined modpack/proxy acceptance remain unverified. The full goal remains open on the [current checklist](remaining-citizens-work-2026-09-26.md).
