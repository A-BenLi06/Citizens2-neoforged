# Bulk NPC removal lifecycle

Registry-wide removal previously walked a live iterator, called trait cleanup
directly, and stopped at the first exception. This could leave later NPCs registered
while their removal snapshots were already in command history. It also bypassed the
NPC's final disposal state and common runnable/goal cleanup.

Bulk removal now captures its initial NPC instances, validates them before mutation,
and disposes each remaining owned instance through the common lifecycle. Cleanup
continues across trait and NPC failures and reports the accumulated error afterward.
Registry cleanup cancels lifetimes and removes exact identity bindings. Entity
release shares the existing forced retirement cleanup, using the removal reason.

The bulk path retains `RemoveReason.REMOVAL` for traits and does not add per-command
removal events. Runtime retirement still uses its separate retirement callbacks and
keeps saved data. Callback-created instances are outside the initial removal set.

Native removal checks inject throwing cleanup into two NPCs and verify both trait
sets are cleared, the correct removal reason is delivered, the default registry is
empty, and bulk undo restores both traits from history. Ordinary save-backend failures
are still reported; arbitrary external callback effects are not rolled back.

The fixture also creates an NPC from the first failing cleanup callback. The new
instance must retain both ID and UUID bindings after the original batch completes.
It is explicitly removed by the fixture before checking the emptied original
registry and undoing the batch. This tests live registry mutation without making the
new NPC part of the original deletion request.
