# Template workspace file resolution

TemplateWorkspace now returns regular files in its namespace's direct-child scope.
It normalizes relative names such as ./tree.yml and compares resolved paths with the
resolved namespace directory. Missing files, directories, invalid names, absolute
paths and references outside that direct-child scope return null.

Previously an existing directory could be returned as a behavior file, while a valid
./-prefixed filename failed the literal parent comparison. Three filesystem tests
cover direct/normalized names, invalid or missing entries, and parent/nested/absolute
references. Existing BehaviorTrait fallback lookup is unchanged; these tests do not
claim end-to-end behavior-script or platform link coverage.
