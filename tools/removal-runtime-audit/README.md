# Removal command audit

Run from the Citizens repository root:

```powershell
./tools/prepare-removal-audit.ps1
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/removal-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

This separate fixture uses loopback port 25581 and the `removal-world` save inside
`artifacts/removal-audit-server`. It exercises actual destructive commands against
NPCs created by the probe. Do not put production NPC data in its configuration.
Preparation refuses a different bind address, port or save name. The probe requires
an initially empty default NPC registry before allowing teardown to clear it.

Coverage includes ID/name/UUID/entity-UUID targeting, ownership and scoped
permissions, owner/world filtering, actual Bukkit world UID files, unknown and
ambiguous world rejection, source events, source-registry restoration, occupied
ID/UUID conflicts and retry, temporary-NPC snapshots/copies, deferred respawn and
preservation of pending trait-removal writes. It also drives ambiguous-name choices
through the registered chat listener and scheduler, including exit, invalid/stale
IDs, and permission/ownership changes while a prompt is open.

The dispatcher checks verify that expected Citizens failures become Brigadier
failures and stop later dialogue actions. The public `executeSafe` handled-result
contract is separate from this dispatcher outcome.

The fixture has 62 checks. Seven item-persistence checks exercise native enchantment
components with live registries, failed inventory encoding without deleting the old
slot, snapshot failure blocking removal and copying, retry after fixing the item,
exact item restoration through undo, and explicit clearing of an inventory slot.
Eleven recovery checks cover unavailable global/per-command costs before any payment,
retained cost definitions, cost/action/stock editor opening and closing, repaired
payments, and actual scheduled rewards on the following server tick.

The runner requires a completion marker and no failure marker, and has a three-minute
task timeout if the subscriber is absent or a startup check hangs. The subscriber
is part of the dialogue source set and therefore subscribes under `interactions`.
Player connections use an embedded transport with NeoForge's mock channel setup;
this is a server-side command/prompt test rather than a real client input test.
Run the normal build afterward to exclude opt-in probe classes from release jars.
