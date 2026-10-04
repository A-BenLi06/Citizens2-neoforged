# Removal preflight during shop transactions

Native NPC destruction, command-sender destruction, trait removal, direct deregistration
and registry-wide removal now validate before changing removal ownership. Trait exposes
a read-only, repeatable validateRemoval hook; the default does nothing. ShopTrait checks
both its private stock and its existing per-NPC shop definition. A definition can be in
use by another NPC's stock even when the owning trait's private stock is idle.

This fixes a failure path where destruction marked the NPC destroyed and removed its
traits/indexes before stock retirement rejected the active transaction. Throwing at that
point left an orphaned active stock/view. Busy requests now fail before removal events,
trait deletion/save markers, entity release and registry changes. Runtime retirement
remains separate from permanent deletion so failed provisional NPC cleanup does not
attempt to delete a shared persistent shop record.
Mandatory creation rollback also bypasses user-removal admission: an NPC whose creation
never succeeded must not be stranded by a trait that denies later deletion requests.

Removal commands preflight before adding undo history or clearing selection. Owner/world
commands collect their complete target set first; remove-all and deregisterAll validate
all targets before beginning any deletion. This is an admission guarantee, not rollback
of arbitrary callbacks that fail after deletion has started. Validators must not mutate
NPCs or cause side effects and may be invoked more than once.

## Evidence and remaining scope

The [native fixture](../tools/trade-removal-runtime-audit/README.md) passes 55 checks on
each of NeoForge 21.1.233 and 21.1.248. It verifies rejected operations preserve NPCs,
traits, selection, history and event counts during both private-stock and definition
transactions, while idle removal still succeeds. A negative control disabling the
destruction preflight fails rejected_destroy_preserves_owner, reproducing mutation
despite a reported exception. An initial fixture counted a command-removal event twice
through its parent event type; the observer was corrected without changing event semantics.

Regression passes 62 removal-command and 305 creation checks. All 320 ordinary tests and
100 Interactions tests pass without failures/errors/skips, followed by a successful clean
test/build. Release archives exclude fixtures. All 78 Interactions entry names/contents,
including its versioned manifest, match the preceding release; ZIP container bytes differ.
The original Citizens save retains SHA-256
`cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

This change rejects destructive requests while the affected transaction is active. It
does not add an automatic post-purchase deletion queue, accept all one-shot shop command
semantics, or establish custom NPC/registry implementations that bypass these native
entrypoints. Those policies and post-admission callback failures need separate acceptance.
Full reload transactionality and physical-client/provider acceptance also remain open.
Local evidence is consolidated in `artifacts/trade-removal-validation-summary.json`.

Interactions remains an independent NeoForge JAR with dependency Interactions → Citizens.
