# Command options and home navigation audit

Run from the repository root with PowerShell 7 and JDK 21:

```powershell
./tools/prepare-command-options-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/command-options-runtime-audit.gradle runServer --console=plain
```

The opt-in fixture uses `artifacts/command-options-audit-server`, bound to `127.0.0.1:25620`, with world `command-options-world` and marker `command-options-audit-fixture.txt`. Preparation validates the existing fixture identity and preserves its world. A fixture-only permission provider and synthetic native player exercise actual command dispatch and packet encoding. Generated NPCs, player sessions and the forced chunk are cleaned up before shutdown; no production data is used.

Success requires `[COMMANDOPTIONSAUDIT] COMPLETE 164 checks`, no `[COMMANDOPTIONSAUDIT] FAILED`, and successful Gradle completion. Coverage includes powered explicit state/admission/persistence, name hover options, home durations/here/precedence, atomic invalid input, permission/ownership/type/selection, world/virtual NPCs, real ticks, paused/pending navigation, exact route ownership, factory exceptions and callback cancellation/replacement/removal/reentry.

Run Gradle/server invocations sequentially and wait for termination. Related regressions are follow, movement-list, armorstand and command-location, followed by `tools/runtime-audit.gradle` and clean ordinary `test build`. Release/source jars must exclude opt-in audit/provider code. Check the consolidated evidence in `artifacts/command-options-validation-summary.json`. Physical-client rendering/input, arbitrary mod extensions and the complete modpack/proxy setup need separate acceptance.
