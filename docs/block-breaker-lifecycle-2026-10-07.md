# Block-breaking task ownership and completion

Block breakers now retain their own navigation parameter identity. Approach parameters
are configured before strategy construction, and reset can cancel a pending request
without canceling a later foreign route. A target that becomes air finishes the task
and stops only its owned approach.

Active tasks also require the captured entity to remain in the captured level and,
when NPC-backed, to remain that NPC's current spawned entity. A transferred player
or mismatched NPC/entity pair cannot continue breaking a block in the old context.

Terminal results prevent repeated custom break effects. Reset is idempotent and marks
the task closed before cleanup or callbacks; visual cracks clear before callbacks can
start a replacement task. Cleanup attempts all categories and reports combined errors.
The behavior wrapper detaches its breaker reference before invoking provider reset,
preventing recursive cleanup from resetting the same object again.

Native tests run on ordinary/packet NPCs with a restored fixture block and counting
break callbacks. They exercise ownership, disappearance, terminal effects, reset reentry
and cleanup errors. A supplied breaker verifies behavior-coordinate flooring and radius
forwarding. Physical-client crack rendering and arbitrary modded break-provider semantics
remain separate acceptance. Interactions stays in its independent NeoForge JAR.
The relocation fixture additionally verifies rejection after a real player dimension
transfer and rejection of a mismatched NPC/entity pair without invoking the break effect.
