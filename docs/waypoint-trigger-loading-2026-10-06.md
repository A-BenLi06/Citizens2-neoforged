# Preserve routes with unavailable trigger data

Permissive nested loading could omit an unknown or malformed trigger while keeping
its route active. A later save could permanently remove the omitted behavior.

Known triggers now decode strictly, and Waypoints prepares each provider through strict
nested loading. Any decoding failure leaves the provider unavailable and retains the
complete original provider snapshot using the existing unavailable-route mechanism.
Saving preserves that snapshot; repairing the data or restoring the missing trigger
provider allows a subsequent load to reconstruct the route.

Unit checks reject malformed known trigger fields without mutating the source and allow
repair. Native checks cover unknown trigger types, malformed delay fields, exact saved
provider-data preservation and recovery. This does not add semantic validation for every
possible numeric value or make custom provider side effects transactional. Interactions
remains an independent NeoForge JAR.
