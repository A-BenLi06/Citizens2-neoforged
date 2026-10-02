# NPC list and selection command audit

Run from the repository root with PowerShell 7 and JDK 21:

~~~powershell
./tools/prepare-selection-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/selection-runtime-audit.gradle runServer --console=plain
~~~

Omit the version override for default 21.1.233. The fixture uses `artifacts/selection-audit-server`, loopback port 25626, world `selection-world`, and marker `selection-audit-fixture.txt`. It requires an empty default registry, tracks its own created NPCs, and releases named registries, grants, players and its forced chunk before shutdown.

Success requires `[SELECTIONCOMMANDAUDIT] COMPLETE`, no `[SELECTIONCOMMANDAUDIT] FAILED`, and successful Gradle completion. Tests use native command dispatch, admitted synthetic players, component click events and the real prompt path.

Coverage includes owned/all/tag/type/owner/registry modes, filter precedence, candidate/player expression scopes, invalid expressions, quoted values and page commands, numeric/UUID/name targets, duplicate registry identities, native list actions, configurable messages, delayed prompt permission/identity, loaded-entity ray and nearest ordering, range, virtual visibility, redirects and console positioning. A custom test engine mutates candidates to verify snapshot/revalidation behavior.

Retained failures distinguish fixture API/output-capture corrections from actual registry routing and silent expression-parse failures. Final evidence is consolidated in `artifacts/selection-validation-summary.json`. Run the Interactions parser tests, creation, relocation, removal, command-location, text-editor and general runtime regressions, then clean test/build. Verify all audit code stays out of release/source jars and Interactions remains separate.
