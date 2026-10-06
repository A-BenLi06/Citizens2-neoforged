# Template validation before reload publication

Templates were previously reconstructed after the new NPC registry was published,
and file errors were logged while the constructor returned a partial registry.
Reload could therefore replace working templates with an incomplete collection.

Reload now prepares a detached, strict TemplateRegistry before candidate NPC loading.
Template read, namespace walk, duplicate-key, migration and folder failures abort
preparation. The old registry, templates and runtime settings remain installed.
Successful publication installs the prepared templates alongside the new registry
before editor cleanup callbacks run. Startup's existing permissive constructor stays
available for reporting individual invalid files without changing startup policy.

YAML replacement actions now use strict construction through their persistence
delegate. A generic strict class-construction overload mirrors existing instance
loading and propagates errors instead of returning a null action.

Tests cover a valid replacement template, malformed YAML, duplicate keys and a file
occupying the template directory. Native reload checks preserve old templates/NPCs
after a malformed file and publish repaired templates on retry. Migration can still
write/rename legacy files during preparation; those disk effects are not rolled back.
Template application side effects and physical-client behavior are separate work.

Trait template parsing now reports a known trait whose parser does not support the
requested short or structured form, rather than silently omitting its action. The
loader's existing error aggregation rejects such candidates. Three tests cover both
unsupported forms and successful application of supported forms, using an injected
parser lookup with the same production parsing path.

Trait template actions now snapshot structured source data and pass a fresh copy to
each parser invocation. Short-form command contexts are validated during preparation
and rebuilt from copied arguments for every application, using the current server
source. Tests verify source edits/parser writes cannot change later structured
applications and mutable command flags are not shared between applications.

CommandEventAction delays event-bus registration until its first NPC application.
Loading or discarding an unused template no longer installs its command listener.
Registration occurs once per action and failed registration may be retried. Existing
UUID membership and exact event-class filtering remain unchanged; already-applied
listener lifetime is not redesigned by this change. Two injected-registrar tests
verify registration timing, repeat application, filtering and retry.
