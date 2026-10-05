# Expression scope inheritance and cached values

Rebinding a lazy supplier now invalidates its cached result. Null lazy results are
cached like other values, and an explicit eager null shadows inherited values.

Variable enumeration includes parent names. Binding-kind and supplier lookup respect
local eager overrides and inherited lazy bindings, so Molang receives the same visible
value that direct scope lookup exposes. Existing eager-over-lazy precedence within
one scope remains unchanged.

Four tests cover cache replacement, null caching/shadowing, inherited names and kinds,
and actual Molang evaluation before and after a local override. resetCache still
clears the current scope's cache; no dependency-tracking or global invalidation model
is introduced.

Lazy result caching is now guarded by a per-binding identity and cache-cycle version.
A supplier may rebind/remove itself or reset the cache while computing; its current
call returns its result, but that result is not cached for the replacement binding
or cycle. Rebinding the same supplier object still creates a distinct binding.
Three tests cover these callback mutation paths.
