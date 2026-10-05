# Sequence child removal

Sequence now adjusts its position when a registered child at or before the active
index is removed, and clears the active reference before resetting a removed child.
RESET_AND_REMOVE uses the same removal path, including cleanup, before selecting its
successor. This avoids retaining a removed node or skipping the next node after the
list shifts.

Admission and execution callbacks are checked for changed ownership before continuing.
Reset detaches the active child/index first and still resets it when parallel cleanup
reports errors. Four tests cover terminal removal, earlier/current child removal,
self-removal during admission, and throwing reset during clear.

These are direct sequence tests. Arbitrary nested controller execution from reset
callbacks and external provider side effects are not covered by a transaction claim.

Terminal and rejected-admission reset callbacks may start a successor. Sequence now
keeps that active successor rather than advancing again or reporting the old failure.
A run revision detects nested execution during admission/execution, preventing the
outer invocation from running a callback-started successor twice. Two tests cover
all terminal statuses and rejected admission with a running successor.
