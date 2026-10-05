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
