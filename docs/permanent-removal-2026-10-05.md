# Entity cleanup during permanent NPC removal

Registry deregistration previously called the cancellable despawn path before
removing indexes. Cancellation could therefore leave the entity alive after its NPC
was unregistered; an exception could instead prevent registry cleanup altogether.

Native deregistration now uses the existing complete entity-release operation. It
attempts ordinary despawn first, then completes entity/controller cleanup if the
entity is still owned. Index and saved-record cleanup proceed even when entity
cleanup reports an exception. Errors remain visible, including suppressed later
cleanup failures. Identity checks preserve callback-installed registry replacements.

Ordinary despawn is still cancellable. This change concerns permanent deregistration,
where the NPC will no longer own a registry entry. Custom NPC implementations keep
their own despawn implementation; native entity-release guarantees are not asserted
for arbitrary providers.

The native removal fixture cancels despawn for a spawned NPC and verifies its entity
is removed, its runtime entity reference cleared, and its registry identity gone.
A second case throws from the despawn event and checks the same cleanup, command
failure reporting, and retained history for undo.
