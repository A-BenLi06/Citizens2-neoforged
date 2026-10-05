# Blackboard key identity across persistence

SimpleMetadataStore normally recognizes built-in NPC metadata names while loading.
Using that mode for a behavior blackboard moved matching names into the typed NPC
key space, while Memory continued reading string keys. A saved blackboard value
could therefore become inaccessible after load.

The native store now offers an explicit string-key loading mode used by Memory,
including when a native store is injected into it. Ordinary NPC metadata loading
continues resolving built-in names exactly as before. Custom providers retain their
own loading contract.

Metadata saving now builds a map with literal key names rather than treating key
strings as DataKey paths. This preserves dotted scalar names as well as blackboard
list names. Existing destination entries are retained by the general metadata store;
Memory still owns replacement of its complete persistence section.

Three focused tests cover a real YAML round trip with built-in-name collisions and
dotted scalar/list keys, injected native storage, and unchanged ordinary typed-key
loading. Legacy data already written as nested paths is not automatically flattened,
because its original key intent cannot be inferred safely.
