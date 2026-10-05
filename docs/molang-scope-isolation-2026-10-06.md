# Isolate compiled Molang evaluations

Compiled expressions reuse a mutable Mocha engine. Each evaluation now saves the
bindings it will override and restores them in finally, including standard bindings
such as math. Removed/omitted variables cannot retain the previous evaluation's
temporary values or suppliers.

Nested evaluations hide outer temporary variable roots unless the inner scope
explicitly supplies them, then restore the outer bindings on return. NPC-free scopes
explicitly disable inv instead of inheriting an outer or previous NPC's closures.
Memory, list, item and placeholder bindings are restored with the same frame.

Seven compiled-expression tests cover sequential variable removal, standard-binding
restoration, nested variable isolation, and nested/later NPC-free inventory binding
access using an NPC interface probe. These are expression tests, not physical-client
or concurrent-evaluation guarantees.

Failure-path checks cover binding setup exceptions after variable installation,
handled evaluation exceptions and uncaught Errors. In each case a later empty scope
sees no residual temporary value, while existing exception propagation/fallback
behavior is preserved.
