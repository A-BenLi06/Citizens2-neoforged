# Command admission and execution scopes

An NPC click now charges the global costs once, alongside the first command that
passes hand, ownership, permission, cooldown and use-limit admission. Empty or
blocked clicks pay nothing. Delayed commands pay when they become eligible, so
retiring them before that point consumes neither global nor per-command costs.
The first global/per-command payment uses one composed transaction: rejection
of the latter refunds the completed global cost. Subsequent LINEAR commands share
that global cost; a later command's rejection cannot reverse an already admitted
earlier command.

Dispatch-event and payment-provider callbacks cannot recursively admit commands
on the same trait for either player. The guard covers delayed admission as well
as the initial click and is released on every exit. Other NPCs retain independent
admission.

Scheduling rejection refunds the relevant completed payment before usage/sequence
state is published. Successful queue submission publishes usage. Once queued,
external dispatcher failures or later retirement do not reverse that payment or
usage: arbitrary commands may already have performed effects. No automatic retry
is attempted.

Temporary permissions are granted around the actual native dispatch, after queued
ownership validation. A zero/negative duration revokes them in `finally`; a
positive duration starts at execution and schedules revocation before dispatch.
No permissions exist while the command is waiting. The scope rechecks ownership
after the permission-provider callback so that callback cannot revive retired work.
If an execution-time provider fails, the dispatcher logs it and skips execution;
the successful queue-admission policy above still applies.

The creation fixture covers global costs on empty/other-hand/use-limited clicks,
event and payment recursion, LINEAR shared costs, payment/scheduling rejection,
and immediate/timed permissions over real server ticks for ordinary/packet NPCs.
