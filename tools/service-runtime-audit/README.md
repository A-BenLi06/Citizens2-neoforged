# Native service integration audit

This fixture tests the actual Yuuniverse Economy API and CmdCam provider in an isolated server. It does not connect a real Minecraft client or change production.

From the Citizens repository root, supply the built Economy 0.4.2 jar, CmdCam 2.2.9 for NeoForge 1.21.1, compatible CreativeCore, and the old world directory:

```powershell
./tools/prepare-service-audit.ps1 -EconomyJar '<economy jar>' -CmdCamJar '<cmdcam jar>' -CreativeCoreJar '<creativecore jar>' -LegacyWorld '<old world directory>'
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/service-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The directory is `artifacts/service-audit-server`; its port is loopback 25580. Preparation copies provider jars and all dimension-specific `cmdcam_Scenes.dat` files into that fixture. It restores the fixture's two test currencies, with different precision, and preserves other existing configuration. The probe seeds only its own catalog entries/accounts, uses real login/logout APIs over embedded connections, and stops the server after its checks. It requires the actual saved scenes from the supplied old world.

The death scene `dead_end1` belongs to the overworld. The three intro scenes `uDays_intro`, `uDays_intro2` and `uDays_intro3` belong to the End. Testing all four against one dimension is invalid.

Checks cover:

- Retired empty aliases, Unicode and quoted shop IDs, correct menu recipients, and absence of preflight menu/balance changes.
- Missing shops/pages, unavailable recipients and wrong-thread API access, with payment preserved.
- Actual ledger mutations, explicit currency ID/display-name resolution, per-currency precision, known offline UUIDs and ambiguous-name rejection.
- Loading all four original scenes, validation without packets, and the exact scene NBT in CmdCam's outgoing `StartPathPacket` for the intended player.
- Missing/empty scenes and missing recipients failing before payment, even though CmdCam's command handler uses a zero return value for both some failures and successful starts.
- A balanced economy ledger and a usable encoded-packet connection after the checks.

The NeoForge mock connection hook supplies negotiated channels for the embedded transport. This is not a real client handshake or a visual camera acceptance test. Run the ordinary build afterward and verify release jars exclude `ServiceBridgeRuntimeAudit` and other opt-in probes.
