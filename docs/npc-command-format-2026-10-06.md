# Shared NPC command formatting

Template command lists and scheduled NPC commands now share shorthand/target
preparation. Only the whole say command is rewritten; similarly prefixed command
names are left intact. Spoken text is encoded through CommandContext.quoteArgument,
preserving embedded quotes and backslashes.

Optional leading slash/outer whitespace are normalized. NPC and waypoint commands
without a parsed id/uuid value receive the NPC placeholder; quoted text containing --id
does not masquerade as an option. Template command lists retain an immutable copy of
their input so later caller edits cannot change pending template actions.

Explicit select/sel positional and named targets are preserved as well. Automatic
unqualified targets now carry the actual NPC instance through the native command
bridge into the command context. Named, anonymous and temporary registries therefore
remain distinct even when their IDs and UUIDs collide. Explicit IDs, UUIDs and registry
qualifiers retain ordinary lookup semantics. Scheduled automatic commands validate
the original instance at dispatch and cannot act on its replacement.

The dispatch scope is consumed once at the native bridge, before command callbacks,
and restored on nested dispatch completion or failure. It matches the original native
input; command parsing independently tolerates extra spaces before the subcommand.
Permission and ownership checks remain in the normal RequirementsProcessor path.

Format tests cover exact command names, argument round trips, quoted flag text,
explicit selectors and prefixed aliases. Scope tests cover nested failure, consumption,
and stale targets. Native template-command checks verify a caller-cleared source
list still renames the intended NPC rather than the console's selected NPC. Command
execution failure policy and arbitrary command side effects remain separate work.
The creation fixture additionally exercises exact target identity in all three
non-default registry kinds, scheduled replacement rejection and explicit default
registry lookup. The separate Interactions artifact owns no part of this change.
