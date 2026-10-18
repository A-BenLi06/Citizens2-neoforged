# RSItemEditor library references

The adapter targets RSItemEditor 1.0.1 and Kotlin for Forge 5.12.0. It calls the
provider's public `Stores`, `NbtItemStore`, `Permissions` and `StorageSupport`
contracts, without reading private fields or rewriting storage files.

`RSItemEditorItems.Reference` explicitly selects `SHARED` or `PERSONAL` and an
opaque saved ID. `snapshot(actor, reference)` returns a defensive native stack,
preserving the saved count and components. `ids(actor, scope)` returns an immutable
list. Every request re-resolves the provider/store and current permission, on the
server thread for the active online actor. Personal references select only that
actor's store; an ID shared by two players does not join their ownership.

References are live, snapshots independent: redefining/deleting a definition
changes future resolutions, while existing snapshots and granted items retain
their components. Missing IDs/providers and empty unresolved native items fail;
there is no fallback into the separate legacy ItemEdit library. Reading through
the adapter does not overwrite provider source data.

`/citizens saveditem <shared|personal> <ID> <amount>` grants an explicit positive
number of items to its player source. Ordinary and packet NPC command traits can
invoke it under their configured player/operator authority. `give(source,
recipient, reference, amount, validate)` uses RSItemEditor's own `giveOrDrop`
behavior after preflight. Shared grants check its native `serverItem("give")`
permission; personal grants require the owning player source and
`itemStorage("get")`. Snapshot/list checks use the corresponding native get/list
nodes. Operator and console treatment follows RSItemEditor's own predicate,
including its operator bypass; this adapter does not invent a second policy.

Separate Interactions adds `rs_item: shared;<ID>;<amount>` for server-authored
shared rewards and `rs_item: personal;<ID>;<amount>` for the player's personal
library. Shared dialogue grants use the server source, as native console grants
do; personal actions retain the player source. Preflight resolves the same
reference/permissions without inserting items. Execution reacquires them. Explicit
amounts replace the snapshot count only for that grant; the stored definition
remains unchanged.

Existing legacy `si give` content retains its explicit local library. The
[source-backed migration follow-up](legacy-rsitemeditor-migration-2026-10-18.md)
adds preview/apply and retained reports for ItemEdit YAML; it does not automatically
rewrite dialogue references. Missing original definitions/providers still require
authoritative resolution under ADAPT-06. Native give/drop semantics do not provide
a cross-mod process-crash transaction.

Validation: 31 combined FTB/RSItemEditor native checks with the actual Paradigm
handler selected, 123 Economy/camera/optional-absence service checks, and 730
ordinary tests with zero failures/errors/skips. Final separate binary/source
archives contain no probes. Logs and hashes are recorded in
`artifacts/rsitem-bridge-validation-2026-10-18.json`.
