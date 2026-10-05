# Behavior template file input

The behavior short-form parser used command argument offsets as if its trait name
were an ordinary argument. CommandContext already excludes that first token, so a
single filename was silently treated as missing and a multiword filename lost its
first word. The parser now requires one argument and joins from argument zero.

Missing arguments, missing/non-file references and unreadable YAML now report an
exception rather than silently returning success. File YAML is read before attaching
or changing BehaviorTrait, preserving the target on input failure. Namespace lookup
and the existing behaviors-directory fallback remain available.

Native tests cover missing arguments, missing files, malformed YAML without unwanted
trait attachment, and a valid behavior filename containing spaces. They invoke the
real short-form parser against a native NPC. Semantic tree validation, replacement
of an already running tree and arbitrary provider side effects remain separate work.
