# Dialogue presentation audit

This opt-in audit runs only in `artifacts/dialogue-display-audit-server` on loopback port 25584. It uses connected server-player fixtures with outbound packet capture and refuses a fixture containing saved Citizens NPCs. It does not require or copy production player data.

Run with JDK 21:

```powershell
.\tools\prepare-dialogue-display-audit.ps1
Set-Location neoforge
.\gradlew.bat '-Pneo_version=21.1.248' -I ../tools/dialogue-display-runtime-audit.gradle runServer --console=plain
.\gradlew.bat '-Pneo_version=21.1.248' -I ../tools/audit-tests.gradle test --console=plain
.\gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The dedicated runtime has 45 checks: private boss-bar/nameplate packets, simultaneous sessions and late joiners, styles/titles/progress, named empty rows and vertical offsets, horizontal offset, chat preservation, placeholder/control-token handling, unchanged option redraw, line/node replacement, manual timing, JSON components, rendering failure, range/dimension exits, logout/reload/shutdown and actual `PlayerList.respawn` rebinding. The packet entities are also checked to be absent from the world entity lookup. Eight additional unit cases cover configuration parsing, all legacy bar styles, progress boundaries, title overrides and hologram geometry.

The source set is attached to `interactions`, and the Gradle gate requires the completion marker and no failure marker. Its timeout is three minutes. A normal build after an opt-in run removes the probe from release outputs; inspect jars for `RuntimeAudit` before delivering them.

These checks verify server state, vanilla packet content and recipient ownership. They are not a physical-client screenshot comparison of fonts, nameplate rendering or the original optional hologram providers.
