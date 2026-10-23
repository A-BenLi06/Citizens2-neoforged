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

Focused validation passed on NeoForge 21.1.248:

- `TemplateEventPersistenceTest`: 5 tests passed, 0 failures/errors.
- Cases cover fresh-lifetime YAML round trip, no application-command replay,
  explicit reapplication and empty snapshots, registry replacement/rollback,
  malformed-record rejection, and registration failure rollback.

This is focused unit coverage, not physical-client acceptance or a claim that
all Citizens behavior is complete. The ordinary client acceptance run remains
deferred by the user.
