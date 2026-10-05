# Queued shop command rollback audit

Run from the repository root in PowerShell 7 with JDK 21:

```powershell
./tools/prepare-shop-command-audit.ps1
./neoforge/gradlew.bat -p neoforge -I ../tools/shop-command-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/shop-command-runtime-audit.gradle runServer --console=plain
```

Check `127.0.0.1:25646` is free; run servers sequentially. Only the synthetic
`artifacts/shop-command-audit-server` is used, and it stops itself. Never copy production
data into it. Rebuild normally before distributing JARs to exclude opt-in fixture code.

Expected: `[SHOPCOMMANDAUDIT] COMPLETE 26 checks` and `BUILD SUCCESSFUL`.
Two admitted players exercise actual CommandAction dispatch, failed costs/results,
throwing later results, a failure after multiple commands are queued, independent
transactions sharing one action, order/repeat preservation, command-list snapshots,
player/operator/console sources, console clicker placeholders and the legacy void helper. Successful chest/merchant
purchases queue actual NPC removal commands, then verify deletion after payment updates
and one return of remaining merchant payments. A failed destructive purchase refunds
items and retains its NPC.

The original CommandAction from `316ca7a` fails rolled_back_commands_never_dispatch.
The fixture deliberately throws one result action and one inventory refresh; their
error handling is part of the test. Command effects already executed are not undone.

This is mock-connection server validation, not physical-client or external command,
economy, permission-provider or network acceptance.
