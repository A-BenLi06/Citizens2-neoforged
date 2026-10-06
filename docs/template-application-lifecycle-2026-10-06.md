# Template application spawn recovery

Applying a template to a spawned NPC now stops before actions if the requested
despawn is rejected. After actions, including a thrown failure, it attempts to restore
spawn state only while the original NPC still owns both registry indexes and remains
unspawned. Failed respawn is reported rather than silently accepted; recovery errors
are suppressed on the original action failure.

This restores entity availability, not arbitrary changes made by earlier template
actions. Recovery uses the NPC's current stored location, preserving intentional
template location changes. Deleted or replaced NPC instances are not resurrected.

Five interface-level tests cover rejected despawn, action failure, failed recovery,
reported spawn failure and lost registration. Native creation checks apply a failing
behavior template to a spawned NPC and verify both error reporting and a restored
entity. The fixture uses the numeric-key sequence representation produced by YAML
loading; an initial raw-list fixture did not execute the intended action and was fixed.

Template application now verifies both registry indexes before starting and before
each action. An action that removes/replaces the target stops later actions, and
an already-unregistered instance is not despawned or modified. Two action-chain tests
cover complete registration loss and UUID-only loss; the stale-target test also
verifies snapshot capture and despawn are skipped.
