# Execute a behavior before mapping its status

`StatusMapper.mapping(child, supplier)` previously returned the supplier's status
without ever running the child. Mapped behavior trees could therefore report
success or failure and advance while silently skipping their actual actions.

Each call now runs the child once before requesting the replacement status.
The supplier can observe the effects of the current child tick. A child exception
propagates before the supplier runs; a supplier exception propagates after the
child's effects. Admission and reset still delegate to the child. The API retains
its unconditional mapping contract, including supplied `RUNNING` and removal
statuses; the separate `singleUse` adapter is unchanged.

Three focused tests cover all child/replacement status pairs, effect ordering,
state-dependent replacement results and exception ordering. All three fail on the
old implementation in `artifacts/behavior-status-mapper-before.log`.

A fixture-only factory runs this existing API through native behavior controllers.
Ordinary and packet NPCs execute three successful child effects mapped to failure
and consumed by an inverter, preserving waits between iterations. A failing child
then records its effect before being mapped to success, allowing the sequence to
reach a signal wait. These probes verify execution, not just final status values.

The fixed implementation passes the three focused tests in
`artifacts/behavior-status-mapper-focused.log` and all 556 creation checks on
each of NeoForge 21.1.233 and 21.1.248 in
`artifacts/behavior-status-mapper-native-233.log` and
`artifacts/behavior-status-mapper-native-248.log`. A clean release test/build
passes 725 tests without failures, errors or skips in
`artifacts/behavior-status-mapper-release-build.log`. Release JAR inspection
confirms separate Citizens/Interactions namespaces and no audit probes. Source,
log and archive hashes are in `artifacts/behavior-status-mapper-validation.json`.

Arbitrary callbacks, external providers and physical-client acceptance remain
separate work.
