# Shared NPC command formatting

Template command lists and scheduled NPC commands now share shorthand/target
preparation. Only the whole say command is rewritten; similarly prefixed command
names are left intact. Spoken text is encoded through CommandContext.quoteArgument,
preserving embedded quotes and backslashes.

Optional leading slash/outer whitespace are normalized. NPC and waypoint commands
without a parsed id value receive the NPC placeholder; quoted text containing --id
does not masquerade as an option. Template command lists retain an immutable copy of
their input so later caller edits cannot change pending template actions.

Four format tests cover exact command names, argument round trips, quoted flag text
and prefixed aliases. A native template-command check verifies a caller-cleared source
list still renames the intended NPC rather than the console's selected NPC. Command
execution failure policy and arbitrary command side effects remain separate work.
