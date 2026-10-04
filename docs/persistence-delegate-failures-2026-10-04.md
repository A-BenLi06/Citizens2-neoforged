# Persistence delegate initialization failures

Implementation base: `68a6a04`. Target: Minecraft 1.21.1, NeoForge 21.1.233 and 21.1.248.

`PersistenceLoader` logged a failed delegate constructor, cached a null and removed the annotated field from its field list. Later saves could therefore report success while omitting the field, and retry could not reconstruct the delegate. A failed explicit registration also replaced the previous type redirect before validating the new delegate.

Delegate construction now throws with the delegate class and original cause. Failed instances and incomplete field lists are not cached. An explicit field annotation must use its declared delegate; initialization failure cannot silently select another type delegate. Registration validates construction before replacing the previous redirect. Unexpected reflective field-read errors also propagate rather than writing an opaque sentinel object.

This lets the native snapshot/store transaction reject initialization failures before publication. Once a recoverable constructor failure is resolved, retry constructs the delegate again and saves every field. External constructor side effects and JVM class-initialization failures retain their normal Java behavior.

Three focused negative tests reproduced the old behavior: a failing declared constructor, a delegate without a default constructor and a failed registration replacing an existing valid redirect. The native save-integrity fixture adds six checks for failed snapshot/store initialization, unchanged data and flush counts, successful retry and repeated construction attempts. These extend the original 98-check fixture to 104 checks per version.

Both NeoForge versions pass all 104 native checks, 208 total. The general regression passes 107 NPC assertions and 223 probes. All 277 ordinary tests and 100 Interactions tests pass without failures, errors or skips, and the default-version clean test/build succeeds. All three modified production/test/fixture checkpoints match the tested files. Release/source archives include the built implementation and exclude audit classes. Interactions entry names and contents are unchanged against `68a6a04`, and the original Citizens save is unchanged.

Final test, source and separate-JAR evidence is consolidated in `artifacts/persistence-delegate-validation-summary.json` by `artifacts/summarize-persistence-delegate.ps1`. Run [the native fixture](../tools/save-integrity-runtime-audit/README.md), the focused `PersistenceDelegateFailureTest` and a normal clean test/build to reproduce.

The [YAML-write follow-up](yaml-storage-writes-2026-10-04.md) now adds atomic file replacement and ordering across storage instances, extending this fixture to 114 checks per version. This is Citizens persistence code. Interactions remains a separate NeoForge JAR and depends on Citizens. Large-store snapshot costs and broader registry/configuration/shop/provider/client acceptance remain open on [the current checklist](remaining-citizens-work-2026-09-26.md).
