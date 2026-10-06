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

Native event integration now applies a template with an on_spawn console command to
a spawned NPC through Template.apply. It verifies first-application execution,
exactly one execution after reapplication, and no execution for an unrelated NPC.
Both NeoForge versions pass the expanded 326-check creation fixture.

Unknown command-event keys now enter TemplateErrorReporter instead of being silently
ignored. Strict registry preparation rejects misspelled event names. File-based tests
cover a misspelled on_spawn key and successful preparation of all six supported
command event/application names.

Command blocks now validate an event mapping and string-list contents. Scalar event
contents, non-indexed maps and non-string command entries are reported instead of
silently becoming empty actions. Empty lists, YAML-normalized numeric-key maps and
direct List values remain supported. Three tests cover invalid shapes, empty lists
and direct non-normalized list input.

Trait blocks now validate their collection shape and entry types before parser lookup.
Both direct Lists and YAML-normalized indexed maps are accepted, including empty
collections. Scalars, non-indexed maps, nested-list entries and invalid structured
names produce errors rather than disappearing. Structured names must be nonempty
strings. Four tests cover rejected shapes, direct mixed-form lists, empty collections
and invalid names.

Template decode errors now retain the original exception as their cause and name the
fully qualified template key. A test deliberately loads a runtime-dependent trait
without an enabled Citizens instance and verifies that the outer file error retains
both template identity and the original runtime exception.

Template entries themselves now require a mapping. String, number, boolean and null
scalars are reported with the full template key instead of becoming empty successful
templates. An explicit empty mapping remains valid. Two file tests cover these root
shape cases.
