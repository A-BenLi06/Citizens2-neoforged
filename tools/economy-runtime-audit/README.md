# Native economy integration audit

Run from the repository root with a compatible real Yuuniverse Economy jar:

```powershell
./tools/prepare-economy-audit.ps1 -EconomyJar 'C:/path/to/yuuniverse-economy.jar'
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/economy-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' '-PeconomyAuditVerify=true' -I ../tools/economy-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The fixture lives exclusively in `artifacts/economy-audit-server`, listens on loopback port 25579, and shuts down after its checks. The setup preserves an existing world and config. Its configured currency must remain `audit`, scale 2. The first pass resets only the fixture's dedicated audit account to 10, exercises the actual native provider and ledger, then leaves 9.7 for the second pass to verify after restart. Run the verification pass immediately after the first pass. Task success requires the expected log marker and no audit failure.

The opt-in Java probe must not ship in release jars. The final normal build removes the additional test source directory from the source set. This audit covers provider lifecycle, decimal batch payment and refund, rejected mutations, and persisted balances. It does not validate every NPC command, GUI, or full modpack interaction.
