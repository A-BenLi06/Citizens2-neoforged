# Behavior retry attempts

`RetryDecorator.retry(child, limit)` now exhausts its finite failure budget.
Previously, retrying called the decorator's own `reset()`, which cleared the
failure counter. Limits greater than one therefore retried indefinitely.
The next run also skipped child admission after resetting the child, breaking
behaviors that initialize their timer or resources in `shouldExecute()`.

Each failed attempt now releases its child and retains the remaining budget.
The next tick admits the child again before executing it. Positive limits bound
total attempts, consistent with the existing limit comparison; zero permits one
attempt, and negative limits remain unlimited. `unlimited(child)` retains that
same negative-limit contract. Successful and removal results end the activation
immediately. Terminal results remain latched until reset or explicit activation.

Reset releases owned child work once and stops execution. A new activation gets
the full budget. Rejected admission ends the activation without running the
child; admission, execution and cleanup exceptions stop retries and preserve
cleanup errors as suppressed exceptions. Revision checks prevent an old callback
result or exception from canceling a replacement activation. Recursive admission
and execution do not run the child again.

The focused tests cover finite/zero/unlimited limits, stateful two-tick attempts,
late success, removal, cancellation/reactivation, admission rejection, failures
and callback reentry. The unchanged implementation fails all eleven tests in
`artifacts/behavior-retry-before.log`. The native negative control also fails
`retry_stops_at_configured_failure_budget_false` in
`artifacts/behavior-retry-native-before.log`.

The creation fixture registers its own provider factory around this existing
public decorator. Ordinary and packet NPCs run parsed wait/action sequences,
exhaust three failed attempts, recover on a third attempt in the next sequence,
then stay at a signal wait. The fixture does not add a new production YAML node.
With the fix, both NeoForge 21.1.233 and 21.1.248 pass all 509 creation checks in
`artifacts/behavior-retry-native-233.log` and `artifacts/behavior-retry-native-248.log`.
The focused suite passes all eleven tests in `artifacts/behavior-retry-focused.log`.
The clean release test/build passes 712 tests with no failures, errors or skips
in `artifacts/behavior-retry-release-build.log`. Archive inspection confirms no
audit probes and separate Citizens/Interactions namespaces. Source, log and JAR
hashes are retained in `artifacts/behavior-retry-validation.json`.
Physical-client and arbitrary provider acceptance remain separate work.
