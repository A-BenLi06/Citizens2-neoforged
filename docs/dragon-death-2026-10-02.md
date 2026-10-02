# Dragon death phases and native completion

The ordinary NPC death coordinator removed its visual after 20 ticks. A dragon can still be flying at that point: lethal damage calls the living death method, then may restore health to one and enter its final flight. After reaching the native terminal condition, it runs a 200-tick death sequence. Direct kill and phase-driven zero health have different entry paths.

## Lifecycle

The dragon damage wrapper lets the native health/phase transition finish before Citizens detaches the accepted death's controller. Cancellation retains native behavior. A hook at the native death tick also handles dying-phase/set-health entry that bypasses the ordinary die method. The full sequence runs under native code; the coordinator waits for actual entity removal instead of assuming a duration.

Citizens' configured respawn delay starts after native animation completion. Sentinel keeps its negative/zero/positive meanings and original ten-tick cadence, also starting after that completion. Direct kills retain immediate native removal and the existing instant-death scheduling path. Manual despawn, removal or replacement terminates the owned visual and obsolete timers. An older damage callback cannot detach or replace a newer entity.

World dragons keep their native world ticks. Only virtual dragons in the dying phase, at zero health, or already owned as a death visual receive the needed native ticks. Ordinary living virtual dragons remain unticked. The existing packet tracker continues health, phase and movement updates for retained viewers; no new viewer acquires the corpse. Native counter/experience processing is preserved. The detached corpse cannot attack or knock back other entities, and its NPC wall-destruction policy remains available through the visual's owner.

Virtual death sound uses the existing viewer set and native global-sound/range rules. It does not broadcast a private virtual NPC's sound to the entire server. Manual replacement releases the previous tracker before a new entity reuses its UUID.

## Verification

The isolated [dragon fixture](../tools/dragon-death-runtime-audit/README.md) compares real and virtual direct deaths, flying/hovering lethal damage, phase-driven entry, cancellation and later death, direct kills, timers and callback ownership. It observes actual native counters, metadata/sound/removal packets and NeoForge experience events. A vanilla dragon retains its 200-tick sequence and offers the same 500-point experience total. Experience-event values are observed with fixture rewards set to zero; this is not a client pickup test.

Early diagnostics are retained separately. The first fixture compile used the wrong native grief-hook signature. Its initial phase assertion also assumed hovering meant flying, but native hover is a sitting phase. A later crowded layout stalled the world flight case at one health; the state report identified it while the other cases had finished. Giving the longer flight a separate native fight origin removed that fixture interference. No production timeout or forced health reset was added.

The expanded 21.1.248 run passes 10,068 checks. Totals include per-tick assertions and can vary with flight duration. Final default-version, regression, build, source checkpoint and archive evidence is recorded in `artifacts/dragon-death-validation-summary.json` and the root walkthrough.

The default 21.1.233 run also passes 10,068 checks. Final regressions pass 1,638 ordinary/armor-stand death checks (the per-item count varies with native loot), 43 packet viewer, 108 tracking-admission and 78 packet-interaction checks, plus 107 NPC assertions and 223 general runtime probes. All 232 ordinary tests and a clean build pass on 21.1.233. Release/source jars include the new production hook and exclude audit code. The separate Interactions archive has unchanged entry names and contents, and the original Citizens save hash is unchanged. No production deployment occurred.

Interactions retains its separate mod/JAR and unchanged implementation. Physical-client animation, audio, interpolation and combined modpack acceptance remain unverified. Native phase behavior can still be delayed by world conditions or external interventions; this implementation follows native completion. The [current checklist](remaining-citizens-work-2026-09-26.md) retains remaining command, data, integration and acceptance requirements.
