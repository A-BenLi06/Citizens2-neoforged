# Item quantity audit

From the repository root in PowerShell 7 with JDK 21:

```powershell
./tools/prepare-item-quantity-audit.ps1
./neoforge/gradlew.bat -p neoforge -I ../tools/item-quantity-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/item-quantity-runtime-audit.gradle runServer --console=plain
```

Check `127.0.0.1:25648` is free; run servers sequentially. The server uses only
`artifacts/item-quantity-audit-server` and stops itself. Never copy production data into
it. Rebuild normally before distributing release JARs to exclude the opt-in fixture.

Expected: `[ITEMQUANTITYAUDIT] COMPLETE 16 checks` and `BUILD SUCCESSFUL`.
A mock-connected player performs four-repeat chest and merchant purchases with duplicate
emerald requirements and limited stock. The fixture verifies exact input/output totals,
both native payment slots, exact stock capacity, legal splitting of 100 items, refund,
and rejection of a multiplication-overflow cost. Eight quantity unit tests additionally
cover merge capacity, repeated merging, large counts, zero/negative repeats, container
limits and placement policies. Original code fails all eight unit tests.

This checks native server state, not physical clients or arbitrary custom container and
component semantics.
