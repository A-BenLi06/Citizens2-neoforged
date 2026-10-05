# Non-mutating blackboard list queries

List size, contains and indexed reads now inspect an existing list without creating
one. Missing/non-list values behave as empty lists for these queries while retaining
their original state. Ineffective removal operations also avoid creating keys or
replacing scalar values.

Indexed writes reject negative indexes before retrieving/creating a mutable list.
Explicit getList, listAdd, listClear and valid listSet operations retain their
existing mutation behavior.

Three tests cover missing/scalar queries, ineffective removals alongside normal list
removal, and failed indexed writes preserving state. These are direct Memory API
tests, without new native/client acceptance claims.
