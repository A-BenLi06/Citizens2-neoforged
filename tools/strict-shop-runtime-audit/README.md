# Strict shop load audit

Run from the repository root using PowerShell 7 and JDK 21:

```powershell
./tools/prepare-strict-shop-audit.ps1
./neoforge/gradlew.bat -p neoforge -I ../tools/strict-shop-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/strict-shop-runtime-audit.gradle runServer --console=plain
```

Check that `127.0.0.1:25642` is free and run servers sequentially. The server uses only
`artifacts/strict-shop-audit-server` and stops itself. Never copy original server data
into it. Rebuild normally before distributing JARs so opt-in fixture classes are excluded.

Expected: `[STRICTSHOPAUDIT] COMPLETE 48 checks` and `BUILD SUCCESSFUL`. The fixture
extends the existing shop-retirement checks with an unknown nested action: reload must
fail before replacing the live NPC or shop map, retain the original source text, and
block overwrite until source recovery. Fixture files are restored in finally blocks.
Repeated runs also load previously saved empty shops, exercising their legacy scalar
representation. Unit tests cover deleted entries, malformed collection/enum values,
literal dotted keys, stale prepared loads and strict-context cleanup.

This audit does not accept stale shop inventory menus or full cross-participant reload
transactions as solved. Those remain separate work.
