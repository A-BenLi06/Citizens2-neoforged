# Timer decorator tick budgets and cleanup

The existing `TimerDecorator.tickLimiter(child, ticks)` API now uses the same
execution state machine as `TimeoutDecorator`. Its old pre-increment check
discarded the last permitted tick: a one-tick timer never ran its child. It also
returned timeout without releasing the child, contrary to its documented contract.

A positive budget now permits exactly that many child runs and accepts completion
on the final permitted tick. If the child is still running after consuming the
budget, the next call returns failure and releases it. Zero and negative budgets
expire without admitting or executing the child. Terminal success, failure and
removal stay latched; subsequent cleanup cannot reset the child twice. A fresh
activation restores the budget and releases any previous active work.

Delegating to the existing timeout implementation also preserves admission
rejection, exception cleanup, suppressed cleanup errors and callback ownership
checks. The shared implementation now defers recursive runs and admission calls
while the child's admission callback is in progress, preventing execution before
the child has finished initializing.

Ten unit tests cover exact boundaries, timeout cleanup, nonpositive budgets,
reactivation, terminal results, rejected admission, exceptions, cleanup callbacks,
and admission reentry through both public APIs. The initial nine tests all fail
against the old implementation in `artifacts/behavior-timer-before.log`. The
additional test protects replacement activation from admission-failure cleanup.
The native baseline also fails `timer_accepts_single_tick_child_false` in
`artifacts/behavior-timer-native-before.log`.

The native creation fixture exposes the existing timer API through a fixture-only
provider factory. Ordinary and packet NPCs run a one-tick action, repeat a two-tick
wait three times under a two-tick budget, skip a zero-budget action, and proceed
after timing out a wait that exceeds its budget. They then remain at a signal wait.
The factory exists only in the audit and exercises the existing public API.

The focused timer/timeout suite passes all 22 tests in
`artifacts/behavior-timer-focused.log`. NeoForge 21.1.233 and 21.1.248 each pass
all 521 creation checks in `artifacts/behavior-timer-native-233.log` and
`artifacts/behavior-timer-native-248.log`.
The clean release test/build passes 722 tests with no failures, errors or skips
in `artifacts/behavior-timer-release-build.log`. Release JAR inspection confirms
separate Citizens/Interactions namespaces and no audit probes. Source, log and
archive hashes are recorded in `artifacts/behavior-timer-validation.json`.

Arbitrary provider and physical-client acceptance remain separate work.
