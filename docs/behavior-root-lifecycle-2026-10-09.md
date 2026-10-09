# Own behavior-root activation and cleanup

BehaviorTrait used to reset a completed tree in its root wrapper and then reset it
again when the controller processed the result. Admission exceptions escaped the
wrapper, leaving the controller holding a tree that had not successfully started.
Admission also reused cached expression bindings from the previous execution cycle.

The root wrapper now tracks whether it has started the wrapped behavior and detaches
that ownership before cleanup. The controller handles ordinary terminal cleanup;
subsequent reset calls do not repeat it. Admission and execution exceptions disable
the affected root, release its active behavior and report the error once. Cleanup
errors are retained as suppressed exceptions when another operation already failed.
An exception from controller-requested cleanup still propagates and disables that
root so subsequent polls do not repeat the failing operation.

Returning FAILURE is an ordinary behavior result and permits a later activation.
Throwing an exception disables that root until the definition is reapplied or rebuilt
on spawn. RESET_AND_REMOVE continues to use the controller's removal semantics.
Revision checks discard results invalidated during callbacks, and recursive run calls
do not execute an action before admission completes or while another run is active.
The expression cache is refreshed before admission as well as before execution.

The isolated creation audit runs success, failure, removal, admission-error,
execution-error and cleanup-error probes through real controllers on ordinary and
packet NPCs. Assertions check exact start/run/reset counts, changed NPC names between
activations, successful replacement and actual despawn/respawn. The old implementation
fails `root_lifecycle_single_cleanup_success_false` in
`artifacts/behavior-root-lifecycle-before.log`.

NeoForge 21.1.233 and 21.1.248 each pass 473 checks in
`artifacts/behavior-root-lifecycle-native-233.log` and
`artifacts/behavior-root-lifecycle-native-248.log`. The restored ordinary test/build
passes 701 tests with no failures, errors or skips; binary/source archives contain
no audit probes (`artifacts/behavior-root-lifecycle-release-build.log`).

This verifies the root wrapper's lifecycle. It does not make arbitrary behavior
provider side effects transactional or establish physical-client/modpack acceptance.
