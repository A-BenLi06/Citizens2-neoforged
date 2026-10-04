# Configuration reads and shop retirement audit

From the repository root in PowerShell 7 with JDK 21:

```powershell
./tools/prepare-shop-retirement-audit.ps1
./neoforge/gradlew.bat -p neoforge -I ../tools/shop-retirement-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/shop-retirement-runtime-audit.gradle runServer --console=plain
```

Confirm `127.0.0.1:25641` is free; run servers sequentially. Only the synthetic
`artifacts/shop-retirement-audit-server` directory is used. The fixture stops itself;
never copy original server files into it. Build normally before distributing JARs.

Expected: `[SHOPRETIREAUDIT] COMPLETE 44 checks` and `BUILD SUCCESSFUL`. These extend
the existing NPC reload lifecycle checks with malformed configuration/shop reads,
file preservation and overwrite rejection, repaired-source recovery, shop retention
after failed candidate cleanup and successful store replacement, and actual shop
deletion on NPC destruction or trait removal. The malformed fixture files are restored
in finally blocks. The original ShopTrait implementation from `47c52ca` fails
`failed_candidate_retirement_preserves_live_shop` and the cleanup assertion
`store_replacement_preserves_shop`.

Settings JUnit tests also inject migration/default-save failure and verify all live
values, duration caches and messaging stay unchanged before successful retry. The
StoredShops JUnit test verifies partial serialization is not published into storage.

Strict nested shop decoding, replacement of deleted shop entries, active inventory
view/editor invalidation and a transaction across every reload participant remain
separate acceptance. This fixture does not prove those behaviors.
