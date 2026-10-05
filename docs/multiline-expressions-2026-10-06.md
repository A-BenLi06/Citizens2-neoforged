# Multiline expression recognition

ExpressionRegistry now recognizes wrapped expressions across line breaks and ignores
surrounding whitespace when recognizing/compiling the wrapper. The expression body
is preserved. Existing wrappers are not added a second time by default-markup logic.

Previously a multiline wrapper could be treated as a plain nonempty string, causing
a condition to evaluate true without compiling its expression or rejecting an unknown
engine. Three tests cover multiline arithmetic/false conditions, whitespace around
the wrapper, unknown-engine rejection and case-independent engine selection.

An additional filesystem test loads actual YAML literal (|) and folded (>) block
scalars, verifying false conditions remain false and explicit-engine multiline
arithmetic produces the expected result, including YAML's trailing newline.
