# Native anvil text input

Validated: 2026-09-24 21:04:20 +08:00 (UTC+08:00). Minecraft 1.21.1 / NeoForge 21.1.248. Scope follows [the ecosystem guide](neoforge-ecosystem-replacements-2026-09-22.md).

`InputMenus.stringSetter` and `filteredStringSetter` now open a real `AnvilMenu`. The previous implementation substituted `ChatStringInputPage`, which also lacked the required `@Menu` annotation: creating the menu failed before that fallback could open. The isolated negative control reproduces that exception on `42eb414`.

## Input contract

- Native rename packets edit a separate draft for each viewer. Caller titles and initial values reach the native screen; the native input, additional, result and player inventory slot layout is retained.
- Left/right pickup or Shift-click on the result submits the draft. An accepted value returns to the parent and refreshes it; rejection keeps the current draft. Clicking the first input returns without submitting. Dropping, cloning, number/offhand swaps and double-click collection do not submit.
- Empty input becomes `null`. Whitespace and literal words such as `null` and `Not set` remain text. No localized display string acts as a sentinel. The explicit `runChatStringSetter` API retains its previous chat and empty-string conventions.
- Rename input follows Minecraft's character filter and 50 UTF-16 code-unit limit. An existing value that cannot fit the native field unchanged uses the chat page, preserving its complete value. That fallback shares the factory's literal/empty semantics and accepts longer replies.
- The input paper and result are controls. Both inputs reject placement and all three slots reject extraction. Player inventory transfers and dragging remain native; collection and creative cloning cannot copy controls. Confirmation never performs repair, consumes materials or experience, or damages a block. The real cursor is returned once by native removal.
- Native full-state synchronization, including the negotiated NeoForge data-slot channel, corrects vanilla's predicted repair output and cost, including unchanged and empty drafts. The template name follows the current draft, so a corrective content packet does not restore the original text. Drag correction is deferred until completion/reset.

## Lifecycle

Menu ownership now accepts native container subclasses while retaining the current player session, exact container and viewer checks. Recursive confirmation and direct operations on stale containers are ignored; callback transitions or closure are respected instead of popping another page afterward.

Minecraft resets `ServerPlayer.containerMenu` after `removed()` returns. Returning the closing viewer immediately would be overwritten by that reset. Screen closure now schedules a token-owned return to the parent, rechecking the page, player login and current screen on the next tick. Explicit closure or a later transition cancels it. Other viewers still follow the shared page transition.

Chat fallbacks retain viewer ownership and unregister on close, transition, another screen opening or logout. A prompt captures one reply and checks ownership on the server thread before invoking its callback. A replaced prompt cannot consume later ordinary chat or revive after a temporary replacement screen closes. Pending replies remain cancellable until dispatched.

## Validation and limits

The dedicated loopback fixture admits two players through the real PlayerList and runs actual rename, click and close packet handlers. Native stream-codec copies detach outgoing inventory snapshots from later mutations. Its final 113 checks cover initial state/title/layout, confirmation/rejection, literals/empty/Unicode/length/filtering, cursor/inventory/experience preservation, item escape attempts, native player transfers/drag, stale screens and player sessions, recursion/callback lifecycle, shared viewers, deferred returns and long-input chat ownership. The actual `NPCConfigurator` opens the native field, updates an NPC's name and refreshes the parent description.

Sequential regression passes 158 drag, 240 Shift and 136 collection checks, 107 NPC assertions, 223 general runtime probes and 217 ordinary tests with zero failures/errors/skips. Normal binary/source builds and audit-code exclusions pass. Shared lifecycle files were unchanged after those menu/general regressions; removing a redundant anvil cost packet was followed by the final dedicated run and normal test/build. The capture fixture now decodes NeoForge's negotiated full-width data payload as well as the vanilla data packet.

Reproduction from the repository root in PowerShell 7:

```powershell
./tools/prepare-anvil-input-audit.ps1
$env:JAVA_HOME = 'C:\Users\ben_l\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/anvil-input-runtime-audit.gradle runServer --console=plain
```

The fixture uses `artifacts/anvil-input-audit-server`, loopback port 25606 and a disposable world. The audit source is opt-in through the init script and must be absent from normal binary and source jars. Evidence is recorded in `artifacts/anvil-input-validation-summary.json`, including the negative-control log, native archive, final source/fixture hashes and regression results.

This change implements the consumed string factories. Arbitrary user-authored `InventoryType.ANVIL` pages still use the generic menu path and do not gain a complete repair/rename API. Native rename packets contain no container ID and target the currently open anvil; no additional old-packet identity is claimed. Physical-client focus, typing/prediction/rendering, and combined modpack/proxy acceptance remain unverified. The retained providers and default NeoForge version are unchanged; no production data or deployment is involved.
