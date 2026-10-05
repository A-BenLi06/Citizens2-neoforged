# String values in Molang blackboard bindings

The mem/list expression bindings previously coerced stored and searched values to
numbers and returned zero for non-numeric list entries. They now preserve Mocha
string and numeric values when passing them to Memory, and convert retrieved Java
values through the existing Mocha conversion routine. String defaults for mem.get
are also preserved without creating a missing key.

list.remove_at now determines success from a valid index before removal, so removing
a null-valued slot reports success. Invalid or empty indices still return zero.

Two tests compile and execute actual Molang expressions to exercise string memory,
list add/get/contains/remove, string defaults, null-slot removal and non-mutating
queries. The full ordinary suite also covers existing numeric behavior expressions.
No new physical-client or native server run is claimed by this expression-level test.

Molang also exposes mem.size() and mem.clear(). Size counts blackboard entries;
clear removes scalar and list entries and returns 1 when a blackboard is available
(or 0 without one). A compiled-expression test verifies counting, clearing and saving
after clearing without reviving prior data.

Molang list membership/removal compare numeric values across Java number wrappers,
so YAML Integer/Long entries match expression Double values. Decimal comparison avoids
rounding large integers into a different requested value; strings remain distinct
from numbers. Tests cover a YAML round trip, numeric/string separation, signed zero
and values beyond exact double integer precision. Direct Memory list equality keeps
its existing Java-object contract; this adaptation belongs to the expression binding.
