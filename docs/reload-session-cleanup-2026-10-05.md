# Session cleanup after reload publication

World editors and chat prompts used to close before shop publication checked its
prepared generation. Rejected shop preparation therefore retained old NPCs but
unnecessarily terminated their editor/prompt sessions.

Reload now publishes shops and the candidate NPC registry before closing world
editors and chat prompts. A rejected preparation leaves these sessions active.
After publication, editor callbacks see the new registry together with new shops.
Editor and prompt cleanup failures are accumulated alongside shop and old-NPC
cleanup failures; no one failure prevents the later cleanup categories from running.
The final diagnostic refers to previous runtime cleanup, covering all these owners.

The native fixture keeps an editor and prompt active through a stale-shop rejection.
It also injects simultaneous shop, editor, prompt and old-NPC cleanup failures and
checks that new state remains published, sessions end exactly once, the editor sees
the new registry, and all four failures are retained.

These are server-side lifecycle tests with a synthetic player and real session
registries, not physical-client interaction tests. A closure callback can still have
external side effects. Skin-cache invalidation and arbitrary provider mutation are
outside a claim of complete reload transactionality.
