# Replace blackboard contents on load

Native blackboard loading now replaces all stored entries from a prepared candidate.
Previously it reused metadata's ordinary load semantics, which preserve transient
entries. Lists held as transient metadata could therefore survive even when absent
from the incoming blackboard snapshot.

SimpleMetadataStore exposes a separate replacement operation that reads the incoming
data into another store before changing live entries. Memory uses it after copying
and decoding its input. Ordinary NPC metadata loading keeps its existing persistence
and transient-value policy; custom blackboard stores retain their own load contract.

Two tests cover removal of old scalar/list keys, list-to-scalar replacement, loading
an empty snapshot, and failed input preparation preserving the previous blackboard.
