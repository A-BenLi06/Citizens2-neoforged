# Purchase completion and notification audit

Run from the repository root using PowerShell 7 and JDK 21:

```powershell
./tools/prepare-purchase-notify-audit.ps1
./neoforge/gradlew.bat -p neoforge -I ../tools/purchase-notify-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/purchase-notify-runtime-audit.gradle runServer --console=plain
```

Confirm `127.0.0.1:25647` is free and run processes sequentially. Only the synthetic
`artifacts/purchase-notify-audit-server` is used; the server stops itself. Do not copy
production data into it. Rebuild normally before distributing JARs to exclude fixtures.

Expected: `[PURCHASENOTIFYAUDIT] COMPLETE 31 checks` and `BUILD SUCCESSFUL`.
Two admitted mock players buy through real chest and merchant menus. The fixture injects
a receipt-description error and throwing purchase listeners, closes a merchant inside
the purchase event, verifies per-player/global limits and single payment return, and
removes an NPC from a post-commit listener. It also tests successful/failed OpenShopAction
navigation, refunds after merchant closure and cancellation of a rolled-back opening.
Full-inventory cases verify that failed rewards remain reversible and final refunds/
rewards use vanilla drops without leaving hidden stacks in a closed merchant container.
Purchase listeners observe the completed escrow delivery.

Negative receipt control: the original NPCShopItem fails receipt_error_keeps_purchase_counts.
Negative payment control: the original merchant handler fails native_payment_committed_before_event_close.
Without escrow, the full-inventory control fails closed_native_inputs_have_no_hidden_items_0.
Intentional receipt/event error diagnostics are expected; `[PURCHASENOTIFYAUDIT] FAILED`
is never an acceptable successful run.

Rollback cancels queued target openings; it does not reconstruct an origin screen already
closed by OpenShopAction. Physical-client rendering and arbitrary provider side effects
remain separate acceptance.
