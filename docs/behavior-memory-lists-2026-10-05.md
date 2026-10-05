# Persistent behavior blackboard lists

Blackboard lists were kept as non-persistent metadata and omitted by metadata saving,
despite Memory accepting lists as stored values. Memory.saveTo now explicitly encodes
current list entries while retaining the existing scalar persistence policy. Empty
lists and null list elements are included. The metadata store's scalar-only
setPersistent contract is unchanged.

On load, the storage layer's contiguous numeric-key representation is decoded back
into lists, including the empty representation. Non-list maps are retained rather
than guessed into an incompatible shape.

Memory.set takes an owned mutable list copy, so immutable caller lists support later
list operations and caller mutations cannot change the blackboard. Loading uses a
detached DataKey copy, and saving copies each list into its snapshot. Lists changed
through the exposed mutable list API are validated before publication; invalid
elements reject saving without replacing prior saved data.

Three tests cover a real YAML round trip, empty/null-containing lists, list operations,
caller/snapshot isolation and atomic rejection of invalid list contents. Typed/string
metadata-name collisions and arbitrary custom-store behavior remain separate work.
