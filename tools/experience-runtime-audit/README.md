# Experience transaction audit

From the repository root in PowerShell 7 with JDK 21:

```powershell
./tools/prepare-experience-audit.ps1
./neoforge/gradlew.bat -p neoforge -I ../tools/experience-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/experience-runtime-audit.gradle runServer --console=plain
```

Check `127.0.0.1:25649` is free and run processes sequentially. The fixture uses only
`artifacts/experience-audit-server` and stops itself. Do not copy production data into
it. Rebuild normally before distributing JARs to exclude opt-in fixture classes.

Expected: `[EXPERIENCEAUDIT] COMPLETE 49 checks` and `BUILD SUCCESSFUL`.
An admitted mock player exercises level charges/rewards, refunds, invalid/overflowing
amounts, changed affordability, integer bounds, preserved fractional/total XP fields,
native shift purchases and failed-result refunds. Native level-change cancellation,
rewrites, clamping and throwing listeners must not create free purchases or lose XP fields.
Extreme levels are restored before
the fixture exits; they test arithmetic admission, not ordinary gameplay at those levels.

The original ExperienceAction fails negative_cost_rejected. An arithmetic-only control
fails native_event_failure_rejected_CANCEL. Three ordinary arithmetic
tests cover the pure boundary calculations. This does not establish physical-client or
arbitrary provider/callback acceptance.
