# Staged skin cache during NPC reload

Reload previously cleared the skin cache before candidate NPC loading. A failed
reload retained old NPCs but permanently invalidated their cache entries and pending
recipients. Reload now temporarily selects a fresh cache without retiring the old
one. Publication commits the new cache and retires the old; failure closes the scope,
retires candidate entries and restores the old cache and its generation.

Generation allocation is monotonic even when the active generation rolls back. This
prevents an invalidated candidate lookup, no longer present in the cache map, from
becoming current when a later reload begins. Fetch completion and skin application
share the same ownership check. Nested full resets/reloads are rejected while the
cache scope is active; per-name invalidation retains its existing behavior.

Four cache tests cover rollback identity, commit and late-close isolation, nested
reset rejection, and obsolete lookup generations across repeated attempts. They use
an invalid name to avoid network dependence. Native reload checks use the same
no-network cache entry to verify candidate visibility, provider/shop failure rollback
and successful commit. Live HTTP completion and physical-client skin rendering are
not asserted by these checks.

Pending old recipients are preserved by retaining their Skin objects rather than
copying or clearing their recipient maps. External network operations are not undone;
late candidate results are rejected through cache ownership checks.
