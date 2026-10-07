# Block-breaking task ownership and completion

Block breakers now retain their own navigation parameter identity. Approach parameters
are configured before strategy construction, and reset can cancel a pending request
without canceling a later foreign route. A target that becomes air finishes the task
and stops only its owned approach.

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
