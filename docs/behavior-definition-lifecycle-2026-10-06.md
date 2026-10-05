# Retain behavior definitions through saves and respawns

BehaviorTrait previously cleared its cached YAML after saving once and did not retain
definitions read from persistence. Later independent snapshots could omit the tree.
Despawn also removed the runtime behavior with no spawn hook to rebuild it.

The trait now retains a detached definition when loaded and writes it on every save.
Spawn rebuilds a missing runtime tree from that retained definition; an already
installed tree is not added twice. Normal parsing replaces an existing root after
constructing its replacement, and explicit empty loaded data clears the old root.

Native tests compare repeated fresh snapshots, load/save another trait instance, and
drive real spawn/despawn/respawn before checking behavior execution. Custom behavior
factory/reset failures remain outside a claim of transactional provider lifecycle.
