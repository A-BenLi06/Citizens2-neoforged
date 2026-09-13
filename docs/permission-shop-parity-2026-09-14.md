# Native permission shop transactions

Implementation follow-up for 2026-09-14 (UTC+08:00), after `4d209b5`.

## Restored behavior

Citizens now connects an optional permanent permission writer to the selected `paradigm:internal` provider. The bridge uses its public permission assignment and mutation methods, keeps the original service instance bound to the writer, and releases its registration on shutdown. An unavailable provider is not advertised as writable.

The original `PermissionAction` uses Vault with a null/global world. The port therefore writes **permanent global rules**. It preserves contextual and temporary assignments. A normal allow does not implicitly remove a same-named deny: the provider still decides effective access. An explicitly requested legacy `-permission` rule is supported as a denial and tracked independently from the ordinary allow.

Shop actions now prepare reversible changes instead of ignoring `add`/`remove` return values and blindly applying their inverse:

- Permission strings are resolved once, copied, normalized and deduplicated. Editing the original list or changing a placeholder later cannot redirect rollback.
- Existing requested rules are idempotent and are not owned by the new trade. Rollback removes only the newly created provider assignment IDs.
- Permission costs require a removable permanent global rule and the original effective-access eligibility check. Inherited, timed, contextual or OP-default access alone cannot produce unlimited free permission payments. Removing a direct token preserves any inherited access.
- A failed batch compensates its already acknowledged writes before propagating failure. Later shop reward failure rolls back permission results and completed costs through the existing shop transaction coordinator.
- Consumed rules are restored through the provider and checked against the original record ID. Conflicting replacement records are not overwritten. Rollback attempts all receipts in reverse order; failed receipts can be retried without repeating completed undo operations.
- The editor requires both its normal permission node and a provider advertising reversible changes. Basic third-party writers remain available through the scalar add/remove API but do not become usable in shop trades until they supply that capability.

Permission actions retain the original boolean, per-trade behavior; an item repeat count does not turn a permission rule into a numeric balance. Economic debits/credits still belong to Yuuniverse Economy.

## Validation

The actual Paradigm 2.4.2b fixture passes **101 checks**, including **33 permission-shop checks**, all previous permission/group cases, and the writer-created permanent rule surviving a full restart. The shop checks invoke the actual `NPCShopItem.onClick` path with actual provider records. They cover successful purchases, later reward failure/refunds, exact preservation of pre-existing rules, consumable direct tokens, inherited rights, explicit signed denials, wildcard/Unicode rules and editor capability gating.

The wallet in those shop checks is controlled test infrastructure. It tests the native MoneyAction/rollback contract; it is not evidence of a single atomic database transaction spanning the real economy and permission providers. The real economy integration remains the earlier separately recorded work.

`artifacts/repair-permission-writer-signed248.log` is the final provider acceptance log. The first shop attempt used an unlimited test storage while asserting a finite till balance; the fixture was corrected to finite storage. Earlier failed/pre-signed runs are not acceptance evidence.

Unit fault injection covers partial add/remove failure, a failed receipt read after an acknowledged mutation, preflight/execute state changes, replacement conflicts, idempotent rollback, retrying only unfinished undo, provider detachment, input mutation, signed-rule identity and unavailable scalar-writer guards. The writer has 17 dedicated unit cases.

Final verification: 101 provider checks, 18 absent-provider checks, 44 removal/undo checks, 107 NPC scenario assertions, 223 runtime probes, 40 dialogue unit cases and 148 ordinary tests pass, with no unit failures/errors/skips. The final normal build passes. Release jars exclude both runtime-audit source sets and all provider implementation classes. `artifacts/permission-writer-validation-summary.json` records log paths and jar hashes. The final scalar availability guard is additionally covered by the normal unit run after the complete integration/regression run.

## Operational boundaries and remaining work

This is application-level compensation through the provider's public APIs. Provider persistence failure or unavailable rollback can still require reconciliation; errors are surfaced rather than converted to success. These changes do not claim crash-atomic transactions across unrelated services. Old receipts cannot silently bind to a new provider service instance after a server lifecycle change.

The provider's own command parser still rejects wildcard/Unicode arguments; the native writer API supports those rule names directly. No provider implementation, private field or database is patched or bundled.

GroupManager primary-group replacement/reset remains a separate missing capability, described in [the group-service contract](group-services-parity-2026-09-13.md). Externally visible temporary permission attachments remain unimplemented; permanent records are not used to imitate those scopes. The other native Citizens, Interactions, Quests, saved-item and Sentinel gaps remain in the [current parity summary](npc-parity-status-2026-09-13.md). Production jars and permission data were not changed.
