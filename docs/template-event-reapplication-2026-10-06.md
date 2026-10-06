# Reapplying template event commands

Applying a newly loaded instance of the same template key previously left the old
event commands attached alongside the new ones. A single spawn could execute both.

Template actions now stage their event subscriptions until the other actions finish.
Before the final respawn, successful application installs the new event actions and
removes the old bindings for that template key, registry and NPC UUID. Other template
keys and other NPCs keep their subscriptions. Applying a definition with no event block
removes that NPC's old event commands. Reapplying the same in-memory template remains
idempotent, and unused replaced listeners are unregistered when their last binding is
removed by reapplication.

An ordinary action failure leaves previous event bindings intact. A new listener
registration failure removes bindings already added by that attempt and preserves the
old ones; retry remains possible. Cleanup errors after the new bindings are installed
are reported without restoring already-replaced bindings. Earlier trait/command side
effects are not rolled back.

Registry transfers record explicit reapplication on a candidate NPC, including removal
of all events. Publication therefore cannot copy the old definition back over that
candidate's choice. Existing late-subscription merging remains available for targets
that were not reapplied.

Unit tests cover scoped replacement, deletion, repeated application, action/registration
failure and candidate publication. The native creation fixture loads changed YAML into
a new template registry and checks actual spawn-command counts on two NPCs.

File reload alone does not reapply templates to NPCs, and applications are not persisted
across a complete server restart. These behaviors and physical-client/provider acceptance
remain separate scope. Interactions stays in its independent NeoForge JAR.
