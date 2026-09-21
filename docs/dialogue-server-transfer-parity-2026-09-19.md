# Dialogue server-transfer parity

Implementation base: `4686e35`. Reference: the installed Interactions 2.14.1 plugin and its Arclight server. Target: Minecraft 1.21.1 / NeoForge 21.1.248, JDK 21.

## Original contract

The original `send_to_server: ` action expands placeholders, removes its marker including one separator space, and passes the entire remaining string to `BungeeMessagingManager.sendToServer`. The destination is a proxy server name. It is not split at semicolons, case-folded, trimmed, resolved as a hostname or converted to a port.

`BungeeMessagingManager` writes two Java `DataOutput.writeUTF` fields: `Connect`, then the exact destination. Bukkit maps the logical `BungeeCord` plugin channel to the modern wire identifier `bungeecord:main`. These fields use Java modified UTF-8 and unsigned 16-bit byte lengths, not Minecraft's VarInt/UTF-8 string format. NUL uses two bytes and a supplementary code point uses its two surrogate encodings. Bukkit caps the complete plugin message at 32,766 bytes, leaving at most 32,755 modified-UTF bytes for the destination after the two headers and `Connect`.

`CraftPlayer.sendPluginMessage` sends only when that player's connection advertised the plugin channel. The original silently skips an unavailable channel. It neither checks the proxy's server list nor waits for acknowledgement, retries, confirms arrival or proactively ends the conversation. Later actions in the batch continue after dispatch.

Exact class/jar paths, reference hashes and disassembly are retained in `artifacts/legacy-server-transfer.txt`. The Interactions reference SHA-256 is `ce4824a2bb4317eb9acd40cd4388eebedfab521d96310bf74f2d26f0ac85293e`; the Arclight SHA-256 is `e1cb06154d7bb0e6551c0666fa11416a54ba60efa879e8d7def36b93cd7d8458`.

## Native implementation

`ServerTransfers` exposes `prepare`, `checkAvailable` and `send`, with immutable validated `Request` values. A dedicated-server mod-bus subscriber registers an optional PLAY clientbound codec for `bungeecord:main`. The codec writes only the original message body; Minecraft supplies the outer channel identifier. There is no physical-client registration that could falsely advertise a proxy merely because this mod is installed.

NeoForge 21.1.248 receives `minecraft:register`/`minecraft:unregister` through the normal packet listener and tracks capabilities per connection. Its public `NetworkRegistry.hasChannel` includes negotiated channels, PLAY common-channel advertisements and legacy ad-hoc advertisements. The service requires a registered PLAY codec, the current live game listener and the player's advertised channel. It does not consult an invented server-address mapping or substitute Minecraft's distinct host/port transfer packet.

`Actions` recognizes `send_to_server`, preserving the entire destination suffix after at most the one separator space. Placeholder expansion occurs before preparation and again when a delayed action resumes. Other action bodies retain their previous trimming rules; teleport retains its separately restored literal world-name handling. The port's case-insensitive action verb and optional separator space remain syntax extensions.

Availability and size checks run during batch preflight, before preceding item payment. Execution checks the live connection again. An unavailable channel now fails visibly and stops later rewards instead of reproducing the original silent no-op. Delayed tails revalidate before payment, and a new login cannot inherit the old connection's accepted batch or channel state. No new static player state or scheduler is introduced.

Successful execution means that the request was queued to the advertised connection. Following actions and local saved progress can complete without a proxy acknowledgement, matching the original contract. A network failure, rejected proxy server name or failed destination login cannot be inferred from this one-way protocol. Empty or unusual opaque names are preserved; destination policy belongs to the proxy.

## Runtime and protocol validation

The isolated display fixture adds **51 transfer checks** with its own required `[TRANSFERAUDIT] COMPLETE` marker. Transfer players use `ConnectionType.OTHER` and an empty negotiated payload setup; the fixture deliberately does not use the all-channel mock setup for them. Actual register/unregister packets are encoded, decoded and passed through the real server packet listener.

The checks cover unavailable/unrelated channels, private delivery, exact Unicode/space/semicolon destinations, placeholder suffix preservation, the registered game packet codec and original two-field body, continued reward dispatch, oversized requests before payment, live revocation, session failure/success records, delayed revalidation, closed connections and same-UUID reconnects. The runtime validates emitted requests and lifecycle behavior; no real proxy or second backend is running in this fixture.

Six protocol unit tests verify fixed byte layout, opaque values, NUL/supplementary modified UTF, exact byte-size limits, codec round trips, and malformed/truncated/unsupported/trailing input. The complete dialogue suite passes **83 tests**, with no failures, errors or skips.

Final sequential validation passes on NeoForge 21.1.248: 51 transfer, 89 world, 61 influence, 84 scheduling, 91 native-action and 127 display checks; 83 dialogue unit tests; 107 NPC assertions, 223 general runtime probes and 166 ordinary tests. Unit failures/errors/skips are zero. The normal build succeeds and release/source jars exclude opt-in probes. Evidence is in `artifacts/transfers-validation-summary.json`; the Interactions jar SHA-256 is `030192320225057456b1aa3c679d4795ca68c827eb75d114ba5eff8c74a8302f`.

Recovery on 2026-09-21 verified all four log hashes and all three jar hashes against that summary, their successful build endings, the required completion markers, and the release transfer service/probe exclusions. The implementation files predate these successful runs; no implementation changes were needed during recovery. Unrelated dedicated suites retain their earlier evidence.

## Acceptance boundaries and remaining work

This implements the eighteenth original action verb; **18/18 action names** now have native handling. That is not complete Interactions or Citizens parity. This batch requires a dedicated server and a proxy that advertises and implements the BungeeCord plugin-message channel. Integrated-server transfer and end-to-end arrival through a real proxy/two-backend setup remain unverified. No production proxy, destination or deployment configuration was changed.

NeoForge requires unique payload registrations. This implementation owns the channel; another mod registering the same channel would require shared-provider integration. A read-only constant scan of the documented installed mod directory covered 47 top-level jars, 76 nested jars and 48,819 classes and found no apparent existing owner; this is not proof about future or dynamically assembled registrations.

The wider goal still includes condition grammar, dialogue authoring/configuration and incoming-chat behavior, quests/saved items, permission-group capabilities, Citizens API/parameters, Sentinel and physical-client/full-modpack acceptance. CmdCam retains camera playback and Yuuniverse Economy retains economic APIs.
