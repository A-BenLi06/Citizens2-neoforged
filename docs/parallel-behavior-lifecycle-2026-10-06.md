# Dynamic parallel behavior ownership

Composite now classifies dynamically added parallel behaviors into the same queue
used for constructor-provided parallel children. Removal clears registration and
active execution before reset. RESET_AND_REMOVE deletes the parallel registration,
so the child does not restart on the next tick.

Parallel admission/execution walk snapshots and recheck membership after callbacks.
Self-removal no longer invalidates iteration or triggers a second reset. New children
added during execution become eligible on a later tick. A Selector containing only
running parallel children remains active, and controller clear removes both serial
and parallel registrations before cleanup.

Four controller tests cover dynamic parallel-only execution and clear, terminal
removal, self-removal alongside another child, and delayed admission of a callback-
added child. Arbitrary callback side effects and other composite scheduling policies
remain outside these focused lifecycle checks.
