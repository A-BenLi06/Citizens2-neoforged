# Parallel composite execution cycles

ParallelComposite previously returned SUCCESS before admitting any child on its first
run. It now admits children at the start of a cycle, ticks active children, and reports
completion only when no admitted children remain active. Finished children are not
restarted while slower siblings continue. Individual terminal failure still follows
the existing contract: it completes that child without failing the whole composite.

Reset permits a new cycle. Children marked RESET_AND_REMOVE are permanently removed
from registration and do not return in the next cycle. Recurring parallel backgrounds
in other composites retain their existing admission behavior through a separate
tickParallel mode.

Three direct tests cover mixed completion timing, reset/removal and empty composites.
A parser test verifies a YAML parallel container actually executes both expression
children. No fresh native/client run is claimed for these core behavior tests.
