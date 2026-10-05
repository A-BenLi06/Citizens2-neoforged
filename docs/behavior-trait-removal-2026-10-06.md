# Stop behaviors when their trait is removed

BehaviorTrait now releases its runtime root on trait removal as well as despawn.
It clears its owned reference before requesting controller removal. The default
retirement hook also reaches this cleanup.

Selector now clears its executing reference when removing the active child, before
resetting it. A reset exception cannot leave that child scheduled to run again.
Admission/execution callbacks are checked for changed ownership before the selector
continues, so self-removal cannot dereference a cleared child or reset a replacement.

Three controller tests cover active removal, reset failure, and self-removal during
admission or execution. A native check removes BehaviorTrait from a live NPC and
verifies its behavior stops. Parallel composite cleanup and arbitrary callback side
effects remain separate lifecycle concerns.
