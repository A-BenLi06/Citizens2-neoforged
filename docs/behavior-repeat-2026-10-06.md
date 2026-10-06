# Counted behavior repetition

The repeat factory previously counted run calls without executing its configured child
nodes. It now uses the tree parser's child-building callback and wraps the resulting
child or sequence in a counted Repeat behavior. It uses its own expression registry,
so parsing does not require a global Citizens instance.

For example:

```yaml
repeat 3:
  - set counter `mem.get('counter') + 1`
```

The mapped form accepts a `count` field alongside numeric child entries. Counts can
be expressions and must evaluate to non-negative integers within range. They are read
once per activation. Zero skips the body; each positive iteration starts, runs and resets
the child. Iterations yield between runs instead of executing an unbounded tight loop.
Child failure or removal terminates repetition with the same status. Reset releases
active work without reevaluating the count. Revision checks preserve newer activation
or cancellation performed during child cleanup.

Direct lifecycle tests cover multi-tick children, dynamic/zero counts, failure/removal,
reset and cleanup reentry. Parser/Molang tests verify real child effects, mapped dynamic
counts and strict invalid-count/unknown-child rejection. The native creation fixture
also runs ordinary and packet NPC behavior trees over server ticks, checking delayed
progress, exactly three effects, zero-count skipping and the following node. Arbitrary
provider effects remain separate acceptance. Interactions remains independent.
