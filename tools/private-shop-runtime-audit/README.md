# Private NPC shop storage lifecycle audit

Run from the repository root in PowerShell 7 with JDK 21:

```powershell
./tools/prepare-private-shop-audit.ps1
./neoforge/gradlew.bat -p neoforge -I ../tools/private-shop-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/private-shop-runtime-audit.gradle runServer --console=plain
```

Check `127.0.0.1:25644` is free; run processes sequentially. The synthetic fixture uses
only `artifacts/private-shop-audit-server` and stops itself. Do not copy production data
into it. Rebuild normally before distributing JARs to exclude opt-in audit classes.

Expected: `[PRIVATESHOPAUDIT] COMPLETE 28 checks` and `BUILD SUCCESSFUL`.
Six admitted players use embedded connections. They hold private chest/editor/trader
views, a shared shop view, an unrelated named-registry view with the same NPC UUID/ID,
and a provisional candidate view. Tests cover failed provider loading, mid-trade store
replacement rejection, failed old-store saving with resumed stock admission, editor
commit before saving, successful replacement, candidate cleanup, independent old/new
stock records, unaffected shared/named views and retired-stock rejection.

Loading failure preserves old private screens. Once provider loading succeeds, private
screens close before saving; a save failure resumes stock admission and retains edits
in live memory, but does not automatically reopen those screens. This distinction is
intentional and tested. Unit tests cover stale resume tokens and cleanup failures.

This tests server-side behavior with mock connections, not physical clients, arbitrary
external provider side effects or all nested destructive command actions during trades.
