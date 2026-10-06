# Behavior signal instance ownership

Listener registrations now belong to an NPC object, using weak identity keys. NPC-based
emission, removal and clearing cannot affect a different NPC merely because its UUID
matches, including instances in different registries or replacement instances.

The directed emit_signal_to behavior sends to the actual NPC resolved by its ID/UUID
lookup. The explicit emitToNPC(UUID, signal) API still matches registered instances by
UUID, and emitGlobal notifies all registered instances. Callers that already have an NPC
should use emit(NPC, signal) when they require exact-instance delivery.

Tests cover colliding UUIDs, independent removal of the same callback object, explicit
UUID/global delivery and existing snapshot/failure behavior. The native creation fixture
also verifies these routing boundaries with real same-UUID NPCs and resumes ordinary/
packet NPC behavior trees after signal delivery. Arbitrary provider combinations remain
separate acceptance. Interactions remains an independent NeoForge JAR.
