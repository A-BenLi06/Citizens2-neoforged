# Legacy ItemEdit to RSItemEditor

The separate Interactions mod now provides:

```
/interactions migrateitems rsitemeditor preview
/interactions migrateitems rsitemeditor apply
```

Both commands read the current `config/interactions/items.yml` using the active
material aliases and server registries. They require `interactions.admin`; native
RSItemEditor `serverItem("save")` authority is checked for each target, including
preview and identical existing entries. Imports belong to the shared store because
the source database is server-owned. Personal stores are never selected implicitly.

Each run creates a unique directory under `config/interactions/migration-reports`.
It contains an exact `source.yml` backup and `report.json` with source SHA-256,
timestamp/offset, original IDs, resolved native SNBT snapshots and per-item results:

| Result | Meaning |
| --- | --- |
| WOULD_IMPORT | Preview found an absent target. |
| IMPORTING | Apply recorded its intent before calling the provider. |
| IMPORTED | Provider accepted the write and its snapshot read back identically. |
| UNCHANGED | Same ID, count and native components already exist. |
| CONFLICT | Existing target differs; it was not overwritten. |
| UNAVAILABLE | Source cannot be converted without losing unsupported data or requires a missing provider. |
| FAILED | Permission, provider or serialization failure; inspect the detail. |

The source is never rewritten. Neither command rewrites dialogue `si give`
references or guesses ID mappings. Switch authored rewards to explicit
`rs_item: shared;<ID>;<amount>` only after reviewing the report. Existing legacy
grants retain their explicit requested quantities; source snapshot amounts are now
preserved when converting definitions, including internal NBT definitions.
Legacy `nick` values are retained separately in the report's `legacyNicknames`
map and source backup. They do not become additional RSItemEditor IDs: no native
alias contract has been established. The original primary IDs remain authoritative.

The exporter deliberately accepts the implemented source schema only. Unknown
fields, malformed definitions, missing types/components and invalid quantities are
reported rather than silently discarded. IDs must be nonblank YAML strings; quote
numeric IDs as in the recovered original database. An invalid whole document leaves
the original intact and fails before target writes. Unsupported records remain in
the exact source backup. Native data-version conversion and installed component
codecs remain responsible for internal NBT migration.

Apply persists the plan before target writes and checkpoints each result. A repeat
run treats already identical snapshots as unchanged and differing snapshots as
conflicts. An interrupted `IMPORTING` entry can be reconciled by rerunning against
the preserved source with the same providers/aliases and reviewing its new report.
This does not provide an atomic transaction across the report filesystem and the
provider's NBT store, nor does it establish power-loss durability for that store.

Validation uses the guarded loopback quest fixture on NeoForge 21.1.248 with
RSItemEditor 1.0.1, Kotlin for Forge 5.12.0 and actual Paradigm 2.5.0 permissions.
Synthetic cases check preview, conflicts, exact counts/components, invalid fields,
unavailable providers, source/hash preservation, idempotence and denied authority.
The optional `-OriginalItemFixtureDirectory` preparation argument copies a recovered
item fixture's provider jars and source files into this isolated fixture. It never
reads or changes a running production server.

The recovered source has 140 definitions: 139 can resolve with the pinned content
providers; package 163 still requires the real `eyemod:eyephone` dependency. Missing
source definitions 6, 15 and 144 cannot be reconstructed by importing this database.
These are migration-content acceptance gaps, not reasons to invent replacement items.

The combined fixture passes 42 checks with those providers. The full import reads
all 139 snapshots back identically. A subsequent server process reports
`UNCHANGED=139, UNAVAILABLE=1`, verifying provider persistence across restart as
well as idempotence within a process. Logs and hashes are recorded in
`artifacts/item-migration-validation-2026-10-18.json`.
The separate Economy/camera service fixture passes 124 checks with RSItemEditor
absent, including an apply attempt that records explicit failures and retains the
exact original and backup without affecting player goods.
