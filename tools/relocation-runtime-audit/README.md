# Native spawn and relocation command audit

Run from the repository root with PowerShell 7 and JDK 21:

~~~powershell
./tools/prepare-relocation-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/relocation-runtime-audit.gradle runServer --console=plain
~~~

Omit the property override to test default 21.1.233. The fixture uses loopback port 25624, `artifacts/relocation-audit-server`, world `relocation-world` and marker `relocation-audit-fixture.txt`. It requires an empty default NPC registry, uses generated NPCs and admitted synthetic players, and restores its block edits, grants and forced chunks before shutdown. No production NPC data is loaded.

Success requires `[RELOCATIONAUDIT] COMPLETE`, no `[RELOCATIONAUDIT] FAILED`, and successful Gradle completion. The fixture uses the real command dispatcher, permissions, chat-prompt input, native teleport/admission events, packets and actual server ticks for remount retries.

`tpto` coverage checks cancellation and exactly-once success feedback for regular,
player-shaped and packet NPCs selected from a named registry, plus canceled and
successful native-player cross-world transfers using the explicit source form.

Coverage includes ID/name/quoted name/UUID/global-ID targets, both registries, ownership and permission revalidation, ambiguous-name prompts and replaced identity, canceled spawns, location overrides and invalid input, front/exact player teleport, cursor/fluid/miss behavior, center/front precedence, console contexts, cross-world permission checks, unspawned teleport events, truthful success feedback, native admission rejection, deferred/superseded callbacks, mounted rollback/remount completion, live/retired NPC and native fake-player command sources, and player-NPC sender transport during cross-world teleport.

Retained negative logs reproduce unauthorized global ID fallback and a retired NPC command source remaining usable. Final logs and counts are recorded in `artifacts/relocation-validation-summary.json`. Run mount-transfer, follow, death, removal, command-location and general runtime regressions, then clean test/build. Keep audit code out of release/source archives and verify Interactions remains separate and unchanged.
