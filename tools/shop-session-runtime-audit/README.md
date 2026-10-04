# Shop session lifecycle audit

Run from the repository root in PowerShell 7 with JDK 21:

```powershell
./tools/prepare-shop-session-audit.ps1
./neoforge/gradlew.bat -p neoforge -I ../tools/shop-session-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/shop-session-runtime-audit.gradle runServer --console=plain
```

Check `127.0.0.1:25643` is free. Run processes sequentially. The server uses only
`artifacts/shop-session-audit-server` and stops itself; never copy production data into
it. Rebuild normally before distributing JARs, excluding the opt-in audit classes.

Expected: `[SHOPSESSIONAUDIT] COMPLETE 33 checks` and `BUILD SUCCESSFUL`. Three admitted
players use embedded mock connections. The fixture opens a chest shop, editor and
merchant screen, checks rejection preserves them, makes actual item trades, attempts
reload during a trade, and then replaces the shop. It checks closed/invalid containers,
single return of merchant payments, stale click/direct-trade rejection, cancelled chat
and deferred parent returns, final/idempotent menu disposal despite throwing callbacks,
stale deletion identity, actual deletion and shutdown closure with preserved save data.

Repeated runs restore saved global shops before any NPC ShopTrait is constructed. The
initial run exposed missing built-in action registration in that case; bootstrap now
belongs to the shop action engine. The old chest container's unconditional stillValid
also failed the disposal check and now uses exact menu ownership.

This tests server-side state and native menu paths with mock connections, not physical
client rendering, network latency, real economy providers or arbitrary custom UI code.
