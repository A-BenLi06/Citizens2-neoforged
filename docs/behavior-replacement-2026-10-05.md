# Validate a behavior tree before replacing it

Explicit behavior replacement previously removed the running tree before parsing its
replacement. Unknown behaviors became successful empty operations, losing the old
tree while reporting no replacement failure.

BehaviorTrait now copies the input and parses a candidate before changing controller
ownership or cached YAML. The strict parser reports unknown behaviors, unavailable
expression languages and expression compile failures. Ordinary parser calls retain
their compatibility policy. Explicit empty map/list containers are recognized as
containers instead of unknown leaf names.

Native checks install a counting behavior, reject an unknown replacement, then run
the original again and verify its blackboard identity and saved YAML survive. Three
parser tests cover nested unknown behavior rejection, compatibility-mode restoration,
unknown expression language and a valid empty container.

This protects against parsing failure; arbitrary custom factory side effects and
controller reset exceptions are not rolled back. A replacement currently uses the
existing blackboard, so this is not a transaction over arbitrary provider mutations.

Strict timeout parsing validates a finite, non-negative integer tick count before
narrowing it to int, and wraps invalid numeric conversion with timeout context.
Tests cover invalid literals and valid zero/integer budgets. Ordinary compatibility
parsing retains its previous conversion policy. Values already normalized by the
expression engine cannot be distinguished from their normalized result here.
