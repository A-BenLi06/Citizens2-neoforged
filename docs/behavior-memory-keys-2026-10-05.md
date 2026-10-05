# Behavior blackboard enumeration and clearing

Memory.getKeys no longer returns a placeholder empty list. MetadataStore now offers
key enumeration and clearing; SimpleMetadataStore returns an immutable snapshot of
logical key names and clears both string and typed NPC metadata entries. Memory uses
these operations directly, including changes made through an injected backing store.
Custom stores can implement enumeration/clear; unsupported enumeration is reported
explicitly instead of returning a misleading empty result.

Memory.saveTo now atomically replaces its own data section, preventing removed or
cleared persistent keys from surviving in a reused save tree. Sibling sections are
preserved. [List persistence is covered by a later follow-up](behavior-memory-lists-2026-10-05.md).
Typed/string metadata-name interactions still require separate work.

Four focused tests cover stable key snapshots, loaded/session/directly written data,
typed temporary overrides, clear/save/reload, and deleting one key without affecting
sibling sections. These are storage tests rather than fresh native/client acceptance.
