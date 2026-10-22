# Paradigm inbound evaluation contract

Reviewed: 2026-10-22T13:46:00+08:00 (UTC+08:00). ADAPT-07 remains open.

Fresh upstream review at commit
[`3e22c73d7d8b7dcbaff8b1122c0ffc2ce294a037`](https://github.com/Avalanche7CZ/Paradigm/tree/3e22c73d7d8b7dcbaff8b1122c0ffc2ce294a037)
confirms that `api.PlaceholderService` still exposes registration only.
`api.ParadigmAPI` advertises API version 1 and exposes permissions, messages and
that registration service. Message delivery schedules a send operation; it does
not return evaluated text. The installed 2.5.0 remains the pinned native target.
The reviewed upstream source declares version 2.5.2; this review does not upgrade
or validate it as a replacement provider.

Legacy `eu.avalanche7.paradigm.ParadigmAPI.getServices()` reaches a platform
`replacePlaceholders` implementation, but the companion `apiJar` explicitly
packages only the new API classes and selected registry classes. `Services`,
platform adapters and formatter implementation classes are outside that exported
artifact. Reaching them does not establish the missing companion API contract.
Citizens continues using the supported registration API and owner-native
Economy/FTB queries.

## Proposed provider contract

The provider needs an additive, advertised capability for synchronous placeholder
evaluation. These are requirements for a future supported API, not method names
Citizens currently invokes:

| Requirement | Expected behavior |
|---|---|
| Explicit context | Player UUID or server context; no implicit current player, borrowed operator, or created offline session. |
| Return value | Text plus a typed status; success is distinct from unavailable capability/provider, invalid input, offline player, wrong thread, recursion and evaluation failure. |
| Thread ownership | Read live player/world/provider state only on its owner thread. Reject unsupported worker calls explicitly; never block waiting for the server thread. |
| Lifecycle | A retained service becomes unavailable after retirement. Each new call uses the current provider. Retired services cannot return a successful current-state result. |
| Unknown values | Preserve unknown tokens or identify them explicitly. Unknown, unresolved and empty resolved values must remain distinguishable for conditions. |
| Output semantics | Specify plain text versus escaped render-template output. Preserve literal dollar signs, backslashes and angle brackets according to that declared mode; do not accidentally turn returned player data into formatting directives. |
| Bounded execution | State a recursion policy and single-pass/order semantics for external resolvers; failures must not cause unbounded cross-provider recursion. |
| Effects | Evaluation sends no messages, executes no commands and mutates no game/account/quest state. External resolver side effects cannot be treated as a transactional operation. |
| Compatibility | Advertise the capability and preserve existing registration implementations. A default unsupported result can keep an existing functional interface compatible. |

Citizens can then consume the public contract optionally. Missing/older providers
must preserve text and report unresolved conditions instead of inventing a value.
The integration must document which authored token syntax and surfaces opt into
evaluation, and keep Citizens' owned NPC placeholders compatible. It must avoid
evaluating the same text again through outbound Citizens resolver callbacks.

## Required acceptance

Use an isolated provider candidate and synthetic actors. Verify native provider
and external tokens; console, offline and replaced sessions; wrong-thread calls;
unknown and empty values; literal formatting characters; resolver failure and
recursion; provider replacement/retirement; and no message/command side effects.
Exercise NPC names, text/holograms and intended dialogue conditions independently.
Run provider-absent and registration lifecycle regressions, both ordinary Citizens
builds, separate Interactions tests and archive ownership inspection. Pin the
provider/API artifacts and source before calling this gate complete.

Upstream's current LICENSE is CC-BY-NC-ND-4.0. A distributable modified provider
requires appropriate authorization from its rights holder or an upstream release
with the supported API. This repository contains this original contract proposal;
it does not distribute a modified Paradigm provider. No upstream message, issue or
pull request was sent. No production/client fixture artifact was changed.

Read-only source inventory and SHA-256 hashes are in
[the review record](paradigm-inbound-review-2026-10-22.json). No new runtime
acceptance is claimed by this source review.
