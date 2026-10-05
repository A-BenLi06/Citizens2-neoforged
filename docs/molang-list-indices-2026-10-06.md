# Molang list index validation

list.get and list.remove_at validate their numeric index before narrowing it to an
integer. Fractional, negative, out-of-int-range and non-number values return the
existing failure/empty result instead of addressing a truncated slot. Valid integer
indices retain their behavior.

Two compiled-expression tests cover invalid values preserving the list and normal
integer access/removal. Mocha's NumberValue constructor normalizes NaN and infinities
to zero before a binding receives them; this change does not recover or reject that
lost provenance. The initial test expecting non-finite rejection exposed this engine
contract, which was confirmed by inspecting the dependency bytecode.
