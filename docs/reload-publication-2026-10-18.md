# Coordinated runtime publication during reload

Reviewed and validated on 2026-10-18, UTC+08:00.

Reload now installs the prepared shop maps, skin cache, templates, NPC registry
and template-event ownership before closing any previous shop sessions. Previously,
shop retirement ran while the old registry and templates were still globally
visible. A close callback could therefore resolve a retiring NPC or old shop even
though reload was already committed to replacing both.

`StoredShops.PreparedLoad.publish(Runnable)` validates source generation and idle
trades before publication. The companion publication runs after map replacement
and before any session callbacks. The existing no-argument method uses the same
ordering. Reentrant publication is rejected, and a stale preparation invokes
neither the companion nor retirement. Companion and retirement failures are
post-publication errors; all previous sessions are still retired and their errors
are retained. Citizens continues editor, prompt and NPC cleanup after session
errors, preserving the published runtime and reporting combined failures.

Three unit regressions cover callback visibility, stale preparation, companion
failure and usable state after cleanup errors. The isolated native audit covers
ordinary/packet NPC restoration, rejected preparation, skin/settings/templates,
event ownership, shop/editor/prompt/NPC cleanup failures and reload reentry.
The old implementation fails `shop_retirement_observes_new_shops_and_registry`.
Both NeoForge 21.1.233 and 21.1.248 pass all 61 native checks with the correction.

This establishes the publication order of the built-in reload participants.
Configuration/source-file edits and arbitrary external effects performed by a
custom provider are not rolled back. The fixture does not establish physical
client, proxy, quest, crafting or modpack acceptance. Full port completion remains
subject to the [adopted integration gates](ecosystem-adaptation-goals-2026-10-13.md).
