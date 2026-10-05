# Configuration recovery before NPC reload publication

Reload applied the new configuration before constructing the candidate NPC registry.
When a provider failed, old NPCs survived but runtime settings stayed at the new
values. This left a failed reload with a mixture of old NPCs and new configuration.

The enclosing reload now captures runtime setting values and parsed duration caches.
Candidate loading still sees the new configuration. Any exception before publication
restores the old runtime settings after candidate cleanup, including messaging
configuration. Restoration errors are suppressed on the original failure. Once the
publication phase starts, new settings remain active even if old-instance cleanup
reports an error.

Runtime recovery does not restore the configuration file on disk: operator edits and
durable defaults/migrations remain there for retry. It also does not mark a failed
source read as writable. Two Settings tests cover exact duration-cache restoration
and continued rejection of saving a malformed source.

The native fixture passes 34 checks on NeoForge 21.1.233 and 21.1.248, including four
configuration checks covering candidate visibility, failed preparation, successful
publication and post-publication cleanup failure. This does not establish rollback
of arbitrary callback side effects, skin-cache clearing or all shop-publication
failure cases; full reload transactionality remains broader work.
