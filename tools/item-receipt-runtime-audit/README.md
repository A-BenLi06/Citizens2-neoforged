# Exact item transfer receipt audit

From the repository root in PowerShell 7 with JDK 21:

```powershell
./tools/prepare-item-receipt-audit.ps1
./neoforge/gradlew.bat -p neoforge -I ../tools/item-receipt-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/item-receipt-runtime-audit.gradle runServer --console=plain
```

Check `127.0.0.1:25650` is free and run servers sequentially. Only the synthetic
`artifacts/item-receipt-audit-server` is used; the server stops itself. Never copy
production data into it. Rebuild normally before distributing release JARs.

Expected: `[ITEMRECEIPTAUDIT] COMPLETE 11 checks` and `BUILD SUCCESSFUL`.
One admitted mock player exercises name-only filters with different lore, damage and
repair costs. Failed chest purchases return exact paid items; limited merchant stock
delivers its actual variant, retains actual paid currency and returns the exact remainder.
Reward rollback must not remove an unrelated, loosely matching item already held.
Six unit tests also cover capacity, receipt isolation, configuration changes and repeated
execution/refund. Original code fails all six.

Physical-client rendering and arbitrary external inventory modifications remain separate
acceptance. Refunds cannot silently substitute a different component variant if the
recorded item is no longer available.
