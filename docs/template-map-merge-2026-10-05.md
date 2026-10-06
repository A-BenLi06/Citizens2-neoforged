# Empty maps and default template values

YAML template replacement now treats an explicit empty map as a value. With override
enabled it can clear an existing map; with no prior value it creates the empty
structure. Previously the traversal skipped empty maps entirely.

With override disabled, descending into a nested map or dotted field path cannot
replace an existing non-map ancestor. Existing scalar/list values remain intact,
while absent fields under compatible maps still receive defaults. Override-enabled
templates may replace a scalar parent with the requested nested structure.

The existing dotted-path interpretation remains in place. Three focused tests cover
empty-map replacement/creation, preservation of existing values for nested and dotted
defaults, and explicit overriding of scalar parents. These tests use the actual
replacement action and in-memory NPC snapshots; no new native/client run is claimed.

A real YAML null-replacement test verifies explicit deletion with override enabled,
preservation with override disabled, retained siblings, and no creation of missing
parents or replacement of scalar ancestors merely to delete a nonexistent child.
The existing implementation passes without additional production changes.

Contiguous numeric-key maps produced by YAML list normalization are now replaced as
whole list values. Their null slots are preserved rather than interpreted as field
deletions. This aligns freshly generated and disk-reloaded templates, including
empty/null-containing blackboard lists; default-only replacement keeps an existing
list intact. File/application and native NPC round-trip tests cover this distinction.

Cross-representation tests verify raw Lists and contiguous indexed maps follow the
same override/default rules in every pairing. Sparse numeric maps remain ordinary
mergeable maps, preserving unrelated keys and respecting override on existing entries.
These checks pass without further production changes.
