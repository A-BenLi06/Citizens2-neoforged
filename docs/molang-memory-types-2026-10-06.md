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
