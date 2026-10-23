# Template event persistence

Recorded 2026-10-24T00:43:55+08:00 (UTC+08:00). Citizens core scope: Minecraft
1.21.1.

Applied template event commands now survive saving an NPC and loading it in a
fresh server lifetime. The NPC snapshot stores only the template key and its
supported event-to-command lists. Loading validates the complete snapshot
before registering any listener. Runtime-only extension callbacks are not
serialized.

Restoration installs the saved event listeners directly. It does not reapply
traits, YAML replacements, or `on_template_apply` commands. Editing a template
definition takes effect only when that template is explicitly reapplied, keeping
the existing file-reload behavior. Saving happens before event listeners are
retired during shutdown, while command dispatch is disabled as soon as shutdown
begins.

Registry replacement transfers restored subscriptions without duplicating them;
rejected replacement keeps the original registry's listeners. Malformed or
unknown event records are rejected before existing bindings are changed. Empty
snapshots are explicit, so removed template bindings do not return after a
restart.

Follow-up 2026-10-24, UTC+08:00: registry transfer now unregisters listeners whose
last target was discarded. Cleanup failure after publication keeps the committed
candidate active and leaves failed cleanup retryable. Custom `CommandListExecutor`
subclasses retain their runtime semantics instead of being serialized as an
ordinary command list.

Fresh verification on both NeoForge 21.1.233 and 21.1.248:

| Verification | Per target |
|---|---:|
| Ordinary unit suite and build | 836 tests; zero failures/errors/skips |
| Template persistence tests included in that suite | 8 |
| Native ordinary/packet seed, clone and reload | 42 checks |
| Separate native JVM restart | 21 checks |

The same probe against preserved `f48cf96` sources reproduces the missing
event-snapshot assertion. That failed gameplay is excluded from accepted counts.
The four repaired native processes exit normally. The final unsaved explicit
reapplication survives normal shutdown; restart fires each of the five event
commands once, preserves runtime names and does not repeat application commands
or YAML replacement. Removing hooks and reloading another snapshot leaves them
removed.

The [independent validation manifest](template-event-persistence-validation-2026-10-24.json)
pins logs, source, reports and ordinary packages. Verification rereads stopped
Citizens YAML and scoreboard NBT, checks NPC UUIDs and exact score changes, and
matches every ordinary production class against its native fixture counterpart.
Citizens and Interactions remain separate archives; ordinary packages contain no
fixture/test classes. [Audit instructions](../tools/template-events-runtime-audit/README.md)
describe the isolated loopback fixtures and replay guards.

Retained setup failures launched no Java: the initial ordinary invocation used
an incorrect relative init-script path, and the first native invocation passed
unquoted PowerShell project properties. The first independent recorder assumed
NPC records were always a mapping; native YAML legitimately emits a list for
contiguous IDs. Corrected that reader and verified the existing stopped evidence
without repeating gameplay.

Legacy saves do not identify which templates were historically applied. Explicitly
reapply those templates once to record their hooks. This does not invent missing
provenance or serialize arbitrary Java callbacks. Physical client input/rendering
is outside this native event probe; the ordinary-client run remains deferred by
the user. Full Citizens completion is not yet established.
