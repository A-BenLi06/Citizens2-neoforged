# Packaged provider coexistence audit

This opt-in audit combines the retained ordinary-client candidate archive set
with the accepted current Citizens, Interactions, Economy 0.5.9 and GriefLogger
`.10` artifacts. Use a fresh synthetic fixture; leave the deferred physical-client
server untouched. The runtime is Java 21 / NeoForge 21.1.248 on loopback port
25641. The selected Economy and JEI do not support the 21.1.233 core baseline.

Prepare a new fixture with `tools/prepare-quest-audit.ps1 -FixtureDirectory
<absolute-fresh-artifacts-directory> -WithKubeJS`, then run:

```powershell
python -B tools/integrated-runtime-audit.py prepare --fixture artifacts/<fresh-directory>
```

Compile with `tools/integrated-runtime-audit.gradle`, an explicit
`-PintegratedAuditFixture=../artifacts/<fresh-directory>`, isolated
`-PintegratedAuditBuildDir=../artifacts/<fresh-build>` and project cache. Use
PowerShell argument arrays for Gradle properties. Run `compileMainProbeJava` and
`compileDialogueProbeJava`; this init script creates separate probe source sets.

```powershell
python -B tools/integrated-runtime-audit.py package --fixture artifacts/<fresh-directory> --build artifacts/<fresh-build>
python -B tools/integrated-runtime-audit.py run --fixture artifacts/<fresh-directory> --java-home <jdk21> --phase xmod
python -B tools/integrated-runtime-audit.py run --fixture artifacts/<fresh-directory> --java-home <jdk21> --phase quest
python -B tools/integrated-runtime-audit.py run --fixture artifacts/<fresh-directory> --java-home <jdk21> --phase logging
python -B tools/integrated-runtime-audit.py run --fixture artifacts/<fresh-directory> --java-home <jdk21> --phase logging-recover
```

Packaging checks every production class against the accepted release and retains
every release archive entry, then appends only fixture-owned probe classes. These
augmented JARs are not distributable release packages. Existing standalone probes
retain their original behavior when `CITIZENS_INTEGRATED_AUDIT_PHASE` is absent.
The runtime inventory records actual resolved IDs/versions, including nested and
Fabric-bridged mods; the top-level archive count is not the loaded-mod count.

Keep stdin open and observe the same Java handle until actual termination.
WorldEdit can spend minutes enumerating CopyFoxies block states before the first
audit tick. An observation timeout is not permission to replay gameplay. Each
phase directory is single-use. Successful captures require zero process exit,
completion markers, exclusive stopped ownership, SQLite integrity and ledger
balance, and retain complete world/config images plus canonical SQL snapshots.

If a probe assumption fails, preserve its attempt, logs, classes and world.
After repairing the probe, prepare another fresh fixture and use `seed` with
`--parent artifacts/<accepted-parent> --phase <last-accepted-phase>` before
packaging/running its remaining phases. This copies the accepted stopped image;
the parent result remains the authority for inherited gameplay. Do not claim that
the new fixture reran an inherited phase. The 2026-10-23 continuation inherits
only the accepted `xmod` image, avoiding a repeat of its 104 checks.

The quest probe covers either an absent provider or unavailable currency/offer
on an installed provider. It checks that existing bindings/claims/entries are
unchanged, requires the installed provider's `NOT_FOUND` shop reason and verifies
that successful menu preflight changes no menu. The two earlier failed attempts
retain their original absence-only and incomplete exception assumptions.

`prepare --link-providers` can share immutable input archive storage on the same
volume. Packaging atomically replaces owned candidate entries, so it never
overwrites linked release inputs. All provider bytes remain hash checked. This
option was used only for the final fresh fixture to reduce duplicate disk space.

`tools/record-integrated-provider-validation.py` independently checks retained
archives/classes, resolved providers, actual process exits, full SQL snapshots,
immutable financial histories, original journals and completed-launch guards.
This server audit does not accept physical clients or complete original-data
migration, sustained load, every crash boundary or the release gates.

The installed Paradigm 2.5.0 additionally disables holograms on existing-file
loads on Windows. `tools/paradigm-file-audit/ParadigmHologramFileAudit.java`
reproduces this using its actual packaged implementation, Gson and SLF4J. The
provider keeps a reader open while replacing its target file; a closed-reader
replacement succeeds. No modified Paradigm provider is distributed by this work.
