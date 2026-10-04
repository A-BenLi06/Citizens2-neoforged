# Destructive shop-action preflight audit

From the repository root in PowerShell 7 with JDK 21:

```powershell
./tools/prepare-trade-removal-audit.ps1
./neoforge/gradlew.bat -p neoforge -I ../tools/trade-removal-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/trade-removal-runtime-audit.gradle runServer --console=plain
```

Check `127.0.0.1:25645` is free. Run processes sequentially. The fixture uses only
`artifacts/trade-removal-audit-server` and stops itself; never copy production data into
it. Rebuild normally before distributing JARs to exclude audit classes.

Expected: `[TRADEREMOVALAUDIT] COMPLETE 55 checks` and `BUILD SUCCESSFUL`. Six admitted
mock players exercise direct destruction, sender destruction, trait removal, direct
and bulk deregistration, and id/all/owner/world command removal inside a purchase.
The tests run while either private stock or the NPC's shop definition is being traded.
Rejected removals must preserve both registry targets, selection, history, trait data
and removal-event counts. Idle trait removal and command/bulk removal still succeed.
The fixture retains the private-stock replacement checks from the previous audit.
It also verifies mandatory creation rollback cleans up a failed NPC even when a trait
would reject an ordinary user removal request.

The negative control disables only AbstractNPC.destroy's early validation. It fails
`rejected_destroy_preserves_owner_*`: a thrown exception previously did not imply the
NPC had survived. Busy command rejection can emit command-error diagnostics; these are
expected, while any `[TRADEREMOVALAUDIT] FAILED` marker fails the audit.

This implements rejection during an active transaction. It does not enqueue destructive
commands for automatic execution after the purchase, nor promise rollback of arbitrary
side effects after an admitted removal callback starts.
