# Default registry reload audit

Run from the repository root in PowerShell 7 with JDK 21:

```powershell
./tools/prepare-reload-audit.ps1
./neoforge/gradlew.bat -p neoforge -I ../tools/reload-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/reload-runtime-audit.gradle runServer --console=plain
```

Run servers sequentially. The synthetic fixture binds to `127.0.0.1:25639`, uses only
`artifacts/reload-audit-server`, and stops itself. Confirm the port is available first.
Do not copy production data into it. Fixture code is included only through the opt-in
initialization script; rebuild normally before distributing release archives.

The audit injects provider failure after partial restoration, including a spawned
candidate whose trait cleanup throws. It checks old identity/entity preservation,
candidate disposal and suppressed cleanup errors, successful registry publication,
forced retirement despite despawn vetoes, unregistered trait listeners, delayed spawn
cancellation, ordinary and packet transport, another registry's queued respawn with
the same UUID/ID, and cleanup failure after publication without record deletion.

Configuration coverage checks new settings during candidate loading, restoration of
old values/duration caches after provider failure, and retention of new settings
after successful publication or subsequent cleanup failure.

Shop publication coverage invalidates preparation during candidate loading and
verifies that old registry/settings survive. Combined shop/NPC cleanup failures must
retain both errors while publishing the new shop maps and registry together.

Session coverage retains editors/prompts after stale-shop rejection and injects
throwing editor/prompt closures alongside shop/NPC cleanup failures. Closure callbacks
must observe the published registry and every cleanup category must still execute.

Skin-cache coverage checks a fresh candidate cache, rollback after provider/shop
failure, and commit after success using an invalid-name entry without HTTP access.

Template coverage rejects malformed files before publication, retains old template,
NPC and setting state, and publishes a repaired template on a later successful reload.

Invalid-template cases include malformed YAML, a scalar entry after a valid prefix,
and an unknown command event. Each must preserve the old registry/entity, templates,
settings, editor, prompt and skin-cache identity.

Template event coverage isolates identical UUIDs across registry identities and checks
subscription copying during candidate loading, rollback on failure, publication and
cleanup failure after publication. Default-store replacement exercises both rejection
and successful subscription transfer as well.

Expected result: `[RELOADAUDIT] COMPLETE 57 checks` and `BUILD SUCCESSFUL`.
The original implementation at `deb5021` fails `failed_reload_preserves_identity`.
The fixture does not accept shutdown, full configuration/shop transactionality, or arbitrary
external provider side effects as verified.
