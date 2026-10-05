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
