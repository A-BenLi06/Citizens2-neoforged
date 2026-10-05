# Rollback of queued shop commands

CommandAction already scheduled commands for the next server tick through Util.runCommand.
The earlier removal-preflight fixture used direct destructive API/dispatcher calls inside
a transaction; it did not exercise this built-in queued path. Successful built-in command
actions therefore already supported deferred NPC removal. The missing behavior was
canceling queued commands when a later cost/result failed and the purchase rolled back.

Util now exposes scheduleCommand with a cancellation handle while preserving its existing
void runCommand entrypoint. CommandAction keeps handles per transaction and cancels them
on rollback. If its own scheduling/refresh step throws after queuing some commands, it
cancels that partial batch before propagating the error. Another transaction using the
same configured action keeps its own handles. Command order, repeats and execution permissions
retain the existing behavior; commands are snapshotted for that run. Player-sensitive
variables such as `<p>` now use the supplied clicker even for console execution, without
turning the execution source into the player. Player-source interpolation remains single-pass.
Debug output now describes scheduling rather than implying immediate execution.

On the server thread, cost/result rollback happens before the queued next-tick dispatch.
Successful actions remain queued until the purchase and merchant payment-slot updates
finish, allowing a command to remove its NPC after the trade. This does not undo external
effects of commands already executed, nor define rollback for failures after committed
item transfers or arbitrary custom action side effects. Target selectors retain normal
command-dispatch semantics; this is not a durable command queue or retry system.

## Evidence

The [native fixture](../tools/shop-command-runtime-audit/README.md) passes 26 checks on
NeoForge 21.1.233 and 21.1.248. It covers rejected/throwing action rollback, partial-batch
failure, same-action transaction isolation, order/repeats, snapshots, actor permissions,
legacy API dispatch and actual successful/failed destructive command purchases. The
original CommandAction fails rolled_back_commands_never_dispatch in the negative control.

The existing removal-preflight fixture passes 55 checks. All 320 ordinary tests and 100
Interactions tests pass without failures/errors/skips, followed by a successful clean
test/build. Release archives exclude fixtures. All 78 Interactions entry names/contents,
including its versioned manifest, match the preceding release; ZIP container bytes differ.
The original Citizens save retains SHA-256
`cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

The successful one-shot merchant case removes its NPC only after payment slots have
been updated, then returns exactly the remaining payment. The failed one-shot case
refunds its items and does not delete its NPC. Physical-client rendering, external
command/provider behavior and complete reload transactionality remain separate acceptance.
Local evidence is in `artifacts/shop-command-validation-summary.json`.

Interactions remains an independent NeoForge JAR with dependency Interactions → Citizens.
