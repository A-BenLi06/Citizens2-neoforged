# Default registry reload

## Behavior

Ordinary `/citizens reload` now stages NPC restoration in a separate registry. A
provider that throws after partially restoring NPCs no longer destroys the currently
published NPC identities or their live entities. Failed candidates are retired,
including their pending spawns, entity controllers and trait listeners. Cleanup errors
are suppressed on the original provider failure rather than replacing it.

After successful restoration, Citizens publishes the candidate, clears only the old
registry's pending respawns, and retires the old registry. Retirement preserves stored
definitions and releases runtime ownership even when a despawn listener vetoes removal
or a trait cleanup throws. A cleanup failure after publication reports that NPCs were
restored but old cleanup failed; it does not roll back to retired objects. Other named,
anonymous and temporary registries are not part of the default reload.

The default registry object changes on success. Integrations holding it must reacquire
`CitizensAPI.getNPCRegistry()`, as for default-store replacement. The old registry is
retired and cannot accept new NPCs or saves. Failed restoration leaves the previous
binding available. Editors/prompts are abandoned only after provider restoration succeeds.

Configuration reload and skin-cache invalidation still precede NPC loading, so restored
traits see the new settings. This is an NPC runtime ownership guarantee, not a transaction
across configuration, stores, shops and templates. A provider's internal source snapshot
and arbitrary external side effects cannot be rolled back by registry retirement.
Configuration/shop read failures and shutdown cleanup remain targeted work.

## Evidence

The [native fixture](../tools/reload-runtime-audit/README.md) uses synthetic data on
`127.0.0.1:25639`. Running it with the original `deb5021` implementation fails
`failed_reload_preserves_identity`. The fixed implementation passes 30 checks on each
of NeoForge 21.1.233 and 21.1.248, including partial provider failure with throwing
cleanup, normal/packet entities, listener removal, delayed-spawn cancellation, despawn
vetoes, store preservation and another registry's same-UUID/ID pending respawn.

The first fixture run omitted the queued NPC's location and packet transport setup;
that fixture failure is not production defect evidence. A first 21.1.248 invocation
used an unquoted PowerShell property argument and failed during Gradle configuration;
the successful run uses the quoted version argument documented in the fixture README.

Logs: `artifacts/reload-baseline-233.log`, `artifacts/reload-runtime-233.log`, and
`artifacts/reload-runtime-248.log`. Regression and final archive evidence are summarized
in `artifacts/reload-validation-summary.json`.

The existing default-store switch fixture passes 78 checks. All 301 ordinary tests and
100 Interactions tests pass with zero failures, errors or skips, followed by a successful
default-version clean test/build. Each Gradle/server process terminated before the next
started. Release archives exclude fixture classes. The original Citizens save retains
SHA-256 `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Interactions remains an independent NeoForge mod/JAR, with dependency Interactions →
Citizens. All 78 Interactions archive entry names and contents, including its versioned
manifest, match the preceding release. The rebuilt ZIP container has a different hash;
this is not a byte-identical archive claim. Citizens archives contain no Interactions
implementation, resources or embedded JAR, and Interactions contains no Citizens classes.
