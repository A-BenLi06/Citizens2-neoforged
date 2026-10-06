# Behavior wait duration and lifecycle

Wait nodes now read scalar or short-form parameters without overwriting the scalar,
and evaluate their compiled duration once per activation. Returned numbers must be
non-negative integer tick counts within range; returned strings use duration parsing.
Reset clears the countdown without evaluating expressions or causing their side effects.

Zero-duration waits can execute and succeed, allowing enclosing sequences to continue.
Positive waits complete after their configured number of run calls. Each later activation
reevaluates a dynamic duration. Invalid returned values are rejected before a new countdown
is installed. Missing/blank literal parameters keep the existing 20-tick default.

Tests cover scalar/short duration input, countdowns, zero-wait sequences, expression
evaluation counts, reset and invalid results. A parsed sequence uses a real Molang wait
expression and then updates its blackboard. Native-server scheduling and arbitrary
expression-provider callbacks remain separate acceptance; Interactions stays independent.
