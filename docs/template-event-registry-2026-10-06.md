# Registry-scoped template event subscriptions

Template event commands now match both the owning registry object and NPC UUID.
Previously a UUID match in any registry could execute an action applied elsewhere.
Weak identity keys keep unrelated registries distinct without retaining a registry
solely through a subscription record. UUID-based continuity inside one registry remains.

Reload and default-store replacement explicitly prepare a subscription transfer before
loading the new registry. Candidate spawn/event callbacks can use the inherited
subscriptions while the old registry keeps its own bindings. Rejected preparation drops
candidate bindings before runtime cleanup. Successful publication merges subscriptions
added during loading and removes the old registry bindings. Unrelated registries retain
their subscriptions. Errors cleaning old runtime state after publication do not roll back
the new subscriptions.

Unit tests verify identical UUID isolation, rollback, commit and late additions. The native
reload fixture posts events against old, candidate and unrelated NPCs, including failed
provider loads, successful publication, cleanup errors, and default-store replacement.

This migrates subscription ownership when NPC registries change. Replacing previously
applied command definitions after a template file reload is still separate work, as are
persistent application records across full restarts. Command side effects during candidate
loading are not transactional. Interactions remains a separate mod and JAR.
