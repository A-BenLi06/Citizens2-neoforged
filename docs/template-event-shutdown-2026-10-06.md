# Template event subscription shutdown

Applied template commands previously registered consumers on the global NeoForge bus
without retaining a way to unregister them. Superseded template registries could leave
these consumers alive beyond server shutdown.

CommandEventAction now retains its exact listener and supports terminal close. Closing
clears target UUIDs and disables callbacks before unregistering. Registered actions are
tracked independently of the current template registry, so shutdown also closes actions
from older registries. Cleanup attempts every action and aggregates failures; a failed
unregistration remains disabled and is retained for retry.

Citizens closes subscription admission at the start of shutdown. New subscriptions and
reapplication of closed actions are rejected. Server startup reopens admission for new
actions after cleaning up the preceding lifetime. It does not revive old action objects.
Ordinary reload retains the existing applied-command behavior; transferring or replacing
those subscriptions across template reloads remains separate work.

The shutdown fixture checks live subscriptions before a real stop, lack of callbacks
after it, and rejection of both retained and new actions. Unit tests also cover the exact
consumer passed to unregister, continued cleanup after failure, retry, and a new lifetime.
This does not establish physical-client reconnect or a complete integrated-server restart.
Interactions continues to ship independently and receives no implementation changes.
