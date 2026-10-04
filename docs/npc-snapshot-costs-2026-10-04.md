# Native NPC batch snapshot costs

Implementation base: `f361484`. Target: Minecraft 1.21.1, NeoForge 21.1.233 and 21.1.248.

The earlier save-integrity repair isolated every native NPC save by copying its enclosing document before serialization and again at publication. `SimpleNPCDataStore.storeAll` already had an outer snapshot, so saving each NPC repeated those full-document copies. It also reparsed every stored ID before every NPC. A native synthetic benchmark confirmed that 512 NPCs allocated about 2.92 GB and took about 1.4 seconds for one in-memory batch save.

## Transaction behavior

`DataKey.writeAtomically` provides a writer scope at the original path, including absolute namespace access. Its default implementation retains the full-copy behavior for other storage implementations. `MemoryDataKey` takes a complete detached document for the outer write, then stages nested writes by accessed branches. Every changed value is prepared and detached before committing into the parent scope. An inner failure leaves parent changes intact and permits retry; an outer failure discards successful inner writes as well.

Raw maps, lists and arrays remain writable inside the scope. Map entry/key views support updates and removal. Retained writer keys and supported mutable values cannot alter the published document. Whole-root replacement, clearing, literal dotted keys and multiple nesting levels retain their behavior. Final outer publication detaches the complete document. `copyWithRoot()` still makes a full detached copy, including unread branches; its contract was not weakened to obtain the improvement. Access remains synchronous and requires caller-side serialization. Mutations made through external references outside the supplied key are not rolled back.

Native NPC saves and store batches now use this writer scope. NPC identity checks, recursive-save rejection, removal intent, unavailable data and all-or-nothing batch publication remain in place.

The store caches parsed record IDs within one batch while the complete set of record names is unchanged. A callback can add, delete or rename a key without changing the total count, so count-only caching would be incorrect. Key-set equality controls reuse; changed keys rebuild the index. Retained `DataKey` paths resolve against the current staged root. Same-count renaming preserves numeric spelling and unknown data, while newly ambiguous IDs are rejected before the next NPC writer runs.

## Measurements

The same opt-in native fixture was used before and after the implementation on NeoForge 21.1.248. Each size contains real unspawned pig NPCs, nested synthetic unavailable-provider maps/lists and a global namespace value. Results below are medians of three samples after two warmups per operation. MB means 1,000,000 current-thread heap-allocation bytes. These measurements exclude YAML/disk writes and other threads.

| NPCs | Previous batch time (ms) | Updated batch time (ms) | Previous allocation (MB) | Updated allocation (MB) |
|---:|---:|---:|---:|---:|
| 64 | 27.46 | 9.82 | 47.03 | 2.44 |
| 128 | 73.19 | 12.63 | 184.50 | 5.51 |
| 256 | 336.26 | 42.90 | 732.98 | 14.18 |
| 512 | 1401.07 | 68.46 | 2920.40 | 40.79 |

The 512-NPC allocation reduction is 98.60%. Timing varies with JVM compilation, garbage collection and the host. These samples are not a production-server latency guarantee. Standalone saves still copy the enclosing document and show similar allocation, with noisy timings; this does not make every save path faster. Record-name comparisons still scan keys, and load-time per-NPC document copies retain separate work.

The fixture also runs successfully on default NeoForge 21.1.233. Raw samples and comparison hashes are in `artifacts/snapshot-cost-baseline248.json`, `artifacts/snapshot-cost-optimized-index248.json` and `artifacts/snapshot-cost-optimized233.json`. The first intermediate optimization, before record-index reuse, is retained separately and is not the final benchmark result. Reproduction instructions: [native snapshot cost audit](../tools/snapshot-cost-runtime-audit/README.md).

## Validation and ownership

Twelve transaction tests cover rollback, nested retry, successful inner writes followed by outer failure, raw-value edits, retained aliases, literal paths, global namespaces, root replacement, map views, preparation failures, unrelated-payload traversal and three nesting levels. Two store tests cover same-count record renaming and new ambiguity during a batch. The 37 focused storage tests pass. A negative run using the full-copy fallback fails the unrelated-payload traversal assertion. Earlier development failures involved a fixture's `Map.Entry` qualification and a counter bypassed by `LinkedHashMap.forEach`; they are not production defect evidence.

Both NeoForge versions pass the existing 114-check native save-integrity fixture, 228 checks total. Related regression passes 78 default-store replacement checks, 193 provider-cycle checks, 305 creation checks and 62 removal checks. The creation fixture intentionally reports its existing creation/removal-listener failures; the verifier requires those exact two diagnostics. General regression passes 107 NPC assertions and 223 probes. All 301 ordinary tests and 100 Interactions tests pass without failures, errors or skips, followed by a successful default-version clean test/build. Each Gradle/server invocation terminates before the next begins. All eight modified implementation/test/fixture checkpoints match the tested source.

Release/source archives include the built transaction implementation and exclude audit classes. Citizens contains no Interactions implementation/resources/embedded JAR; Interactions contains no Citizens classes and all its entry names and contents match the `f361484` baseline. Interactions remains its own NeoForge JAR with dependency Interactions → Citizens. The original Citizens save remains unchanged. Evidence is consolidated in `artifacts/snapshot-cost-validation-summary.json` by `artifacts/summarize-snapshot-cost.ps1`.

Single-save/reload costs, broader registry/configuration/shop/provider lifecycle and physical-client/modpack/proxy acceptance remain on [the current checklist](remaining-citizens-work-2026-09-26.md). No production deployment occurred, and this does not complete the full Citizens goal.
