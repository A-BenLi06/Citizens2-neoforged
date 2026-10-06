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

The YAML replacement persister now validates its action/replacements mapping shapes
and override value. Missing options keep their defaults; Boolean and trimmed
true/false strings are accepted, while invalid values are reported rather than
silently coerced to false or an empty replacement. Three tests cover bad maps,
invalid overrides and actual replacement behavior for valid Boolean forms.

Each template file is now decoded into local candidate maps before any entries from
that file are published. Permissive startup may still skip a bad file and load other
files, but no longer retains the valid prefix of the bad file. Duplicate normalized
keys within one file also reject that entire file. Two file tests verify both indexes
remain free of partial entries while independent healthy files load.

Template traversal now sorts loose migration files, namespace paths and template
file paths before processing them. This makes first-error/duplicate diagnostics
repeatable rather than dependent on directory enumeration order. Two file tests
create entries in reverse order and verify sorted file/namespace diagnostics.

Native reload integration now rejects malformed YAML, scalar template entries after
a valid prefix, and unknown command events while retaining old template/registry,
entity, settings, editor/prompt and skin-cache state. Both NeoForge versions pass the
expanded 49-check reload fixture; repair still permits the later successful reload.

Default structured trait parsers now use strict persistence construction so invalid
fields cannot become a null result that is silently skipped. A native Age parser
probe verifies rejected input leaves the target unchanged and repaired input can be
constructed and attached successfully.
