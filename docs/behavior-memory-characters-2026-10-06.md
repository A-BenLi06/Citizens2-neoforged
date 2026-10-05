# Character values in behavior memory

Memory documents Character as an accepted primitive, but the backing metadata store
previously rejected it during persistent assignment. SimpleMetadataStore now accepts
Character alongside String, Number and Boolean. Arbitrary objects remain rejected
before replacing the previous entry.

Two tests cover storing a Unicode character, an actual YAML round trip preserving
its text, and rejection of an unsupported object while retaining the prior value.
YAML represents characters as strings; this change does not add Java-specific tags
or promise that Character and single-character String retain distinct disk types.
