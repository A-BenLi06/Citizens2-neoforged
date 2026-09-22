# Unavailable item recovery audit

This opt-in fixture uses loopback port 25589 and `artifacts/item-recovery-audit-server/recovery-world`. Its `recovery.yml` belongs to a separate named NPC registry and contains only audit records. Never copy production NPC data into the fixture.

Use JDK 21, prepare from the repository root, and run Gradle sequentially from `neoforge`:

```powershell
./tools/prepare-item-recovery-audit.ps1
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' '-Pitem_recovery_phase=absent' -I ../tools/item-recovery-runtime-audit.gradle runServer --console=plain
# Copy compatible NetMusic/provider jars into artifacts/item-recovery-audit-server/mods.
./gradlew.bat '-Pneo_version=21.1.248' '-Pitem_recovery_phase=present' -I ../tools/item-recovery-runtime-audit.gradle runServer --console=plain
# Move those fixture copies out of mods, keeping recovery.yml and the world.
./gradlew.bat '-Pneo_version=21.1.248' '-Pitem_recovery_phase=absent-again' -I ../tools/item-recovery-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The provider's presence is checked against the requested phase. The same saved definitions pass through actual process restarts; the present run calls the real NetMusic song accessor. Native inventory, equipment, cosmetic equipment, item-frame contents, item-provider suppliers, drop definitions, shop display icons, item actions and limited stock retain or recover their records. Checks cover live entity spawn/despawn, independent NPC snapshots/copies, explicit clears and replacements, empty-provider reload, and actual YAML writes/reads. The original sparse inventory slot remains sparse.

The fixture requires `[ITEMRECOVERYAUDIT] COMPLETE`, rejects its failure marker and limits each run to four minutes. Exact provider/log/source/jar hashes and case counts are recorded in `artifacts/item-recovery-validation-summary.json`. The reference run used the same five actual provider jar copies as the saved-item payload fixture. NetMusic's optional maid recipe warning has the same unrelated boundary documented there.

The removal fixture separately checks command costs before payment, repaired command dispatch on the next scheduler tick, and non-destructive editor opening/closing. Unit tests cover failed encodings, raw data independence, legacy reader hooks, reflective item lists and clone independence. These checks do not play remote music, certify physical-client rendering, or implement the remaining Bukkit metadata decoder.
