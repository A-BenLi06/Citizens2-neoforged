# Shop and NPC publication order during reload

The NPC registry previously changed before shop publication checked whether its
prepared source generation was still current. A provider callback that stored shop
data during candidate NPC loading could invalidate the shop preparation, leaving a
new NPC registry with old shops after reload failed.

Shop publication now runs before the NPC registry swap. A failure before the shop
maps change aborts candidate NPC publication, retires the candidate, and restores
runtime settings. Prepared shop loads expose whether their maps were published so
the enclosing reload can distinguish rejected preparation from cleanup errors.

If old shop-session cleanup reports an error after the new maps were installed,
reload still publishes the candidate NPC registry and retires the old one. Shop and
NPC cleanup errors are both retained. Thus a reported cleanup error cannot leave
new shops paired with an intentionally retained old registry.

Native checks invalidate the shop source from an NPC provider callback, verify old
NPC/entity/configuration preservation, and inject simultaneous old-shop-session and
old-NPC cleanup failures. They verify new shop/NPC publication and error aggregation.
Shop unit tests distinguish rejected active-trade publication from published maps
whose old sessions throw on closure.

Editor/prompt closure and skin-cache invalidation are not rolled back by this change.
Arbitrary provider side effects and direct mutation of public shop maps remain outside
a claim of complete reload transactionality.
