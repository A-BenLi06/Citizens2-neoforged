# Behavior clear and reset failure recovery

SimpleBehaviorController.clear detaches its old registered children and execution
flag before reset callbacks. A throwing reset cannot leave those children executable,
and new children added by a reset callback are not erased afterward.

Selector and Composite reset detach their active execution references before invoking
callbacks. Parallel-child failures are accumulated while all remaining children and
the selected child are reset. A repeated reset does not revisit the old execution
references after a failed cleanup.

Three tests cover clear failure, callback-added replacement behavior, and combined
parallel/selected-child reset failures with error aggregation. These exercise core
controllers directly; arbitrary callback side effects and parallel scheduling outside
reset are separate concerns.

IfElse also detaches its active branch before reset, preventing repeated cleanup
when that callback throws. A test verifies another branch can subsequently execute.
An additional ParallelComposite test clears its children from an execution callback,
verifies old siblings stop, and starts a callback-added child in the next cycle.
