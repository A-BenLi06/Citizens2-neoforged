# Ordinary Minecraft client acceptance

Prepared 2026-10-22, UTC+08:00. This advances ADAPT-10/11; physical results remain
pending until a person runs the cases and supplies the client evidence.

The candidate is Minecraft 1.21.1 / NeoForge 21.1.248 at **127.0.0.1:25640**.
Its working directory is `artifacts/ordinary-client-acceptance-server`. It uses
the October 22 release Citizens/Interactions binaries, Economy 0.5.6, Rhino
2101.2.8-build.91 and the selected synthetic full-stack JAR set. AutoModpack is
excluded so this candidate does not synchronize a pack into the normal client.
All accounts, permissions, configuration, databases and the flat world are fresh.
`BenLi06` has operator level 4 under its isolated offline UUID. Authentication is
disabled only on this loopback fixture; this does not validate online-mode login.

## Preparation and restart

The local fixture recorded below is already bootstrapped; do not create its NPCs
again. The following preparation instructions apply when reconstructing it in a
fresh directory.

```powershell
./tools/prepare-client-acceptance.ps1 -LibraryDirectory E:/yunniverse-server/libraries
./tools/start-client-acceptance.ps1 -JavaHome '<Java 21 installation>'
```

Preparation refuses to overwrite an existing fixture. Restart with the second
command only; it preserves the world and validates the pinned mod hashes and
loopback settings. Installed server libraries are referenced by absolute path;
game state is local to the fixture. Stop with `stop` in its console. For automated
terminal use, keep stdin open with `exec_command`'s `tty=true`.

After the first `Done`, enter the commands in `tools/client-acceptance/bootstrap.txt`
in order **once**. They create ClientGuide (ordinary player), PacketGuide (packet
player), MarketGuide, ClientMount and DeliveryGuide. Repeating this bootstrap
creates additional NPCs; a restart does not need it. The supplied conversation
binds by NPC name and the synthetic paper quest uses task ID `4c1303`. These are
acceptance data, not imported legacy quest mappings.

## Client setup

Use a separate copy of the existing 1.21.1 / NeoForge 21.1.248 client profile.
The fixture's `client-overrides` folder supplies 13 exact common-side provider
versions, including the newer Rhino needed by XMod. Replace the old version of
each matching mod ID in the copied profile, including files with `[SC]` prefixes;
do not retain two versions. This folder is **not a complete client modpack**:
keep the ordinary pack's other content dependencies. Citizens/Interactions use
vanilla NPC/menu presentation and are intentionally not included in these client
overrides. Leave VR mode disabled for this ordinary-client run.

Record the copied profile's complete mod filenames/SHA-256 hashes, Java/loader
versions and client `latest.log` with the results. Report an exact disconnect
message if negotiation fails; full-stack startup alone cannot establish client
compatibility. Never share launcher access tokens or raw launcher command lines.

## Physical checks

Join as BenLi06, then run `/tp @s 0 -60 0`. Stop at the first defect in a case and
record expected versus actual behavior, NPC name, client/server time and logs.

| Case | Actions and expected result | Status |
|---|---|---|
| Join/render | See both player NPCs, their names, the two villagers and horse. Walk around them and check head tracking and equipment visibility. No ghost copies or disconnect. | Pending |
| Ordinary dialogue | Right-click ClientGuide. Name substitution is correct; movement is restricted during the conversation and restored on exit. Click “Receive one paper”: inventory increases by exactly one. Repeat once and verify one additional item. | Pending |
| Packet dialogue | Repeat with PacketGuide, using the other hand and a held item. One click starts one conversation; the selected option executes once. Check reach from inside/outside the normal interaction distance. | Pending |
| Menu input | Right-click MarketGuide, then open the market through either player NPC's dialogue. The Economy browser opens. Test ordinary, shift and number-key clicks, close/reopen and reconnect; inventory must not gain menu icons or retain ghost items. A fresh ledger may have no offers; actual money/item trades need an explicitly seeded shop. | Pending |
| Item delivery | Obtain exactly three paper. Run `/citizens quest status 4c1303`, then right-click DeliveryGuide or choose “Deliver paper” in dialogue. Exactly three paper disappear and authoritative progress becomes 3/3. Verify the FTB quest screen agrees, claim its one diamond, and repeat the completed delivery with no extra consumption/reward. | Pending |
| Mount | Select ClientMount with `/npc select ClientMount`, use `/npc mount`, ride and dismount. Movement/seat position agree with the server and normal input returns. Check both hand interactions while mounted. | Pending |
| Dimensions | As operator, use `/execute in minecraft:the_nether run tp @s 0 80 0`, then return with `/execute in minecraft:overworld run tp @s 0 -60 0`. The ordinary and packet NPCs reappear once; dialogue/menu input still works. The fixture's Nether terrain is disposable. | Pending |
| Skin service | Select each player NPC and set `/npc skin BenLi06`. Verify the external skin visibly applies after refresh and reconnect; retain service errors if lookup fails. | Pending |
| Reconnect/restart | Disconnect during dialogue and during a menu, reconnect and start fresh sessions. Run `/citizens save`, issue `stop` in the fixture console, restart and rejoin. NPCs and completed quest/reward remain; no stale camera, movement lock, menu or duplicate entity. | Pending |
| Camera | Author a short scene using the installed CMDCam version, save it on this fixture server and record its name/dimension. Trigger `cam-server start <scene> BenLi06` directly, then from a dialogue action. Verify playback and restored controls on completion, interruption and reconnect. No legacy scene/world data is copied by preparation. | Pending |

Record a case as passed only from actual client observation. A second ordinary
client, VR hardware/input, proxy/backend transfer, production permissions/content,
original missing ItemEdit definitions/quest mappings and the broader recovery
matrices retain their separate acceptance gates. No production restart, client
profile modification or deployment is performed by these scripts.

## Packaged server evidence

The complete 64-JAR candidate reaches startup, loads the paper quest and both
name-bound conversation triggers, executes all bootstrap commands and saves the
five NPCs. The interactive process handles `save-all flush` and `stop` and exits
with code 0. The prepare script's existing-directory guard rejects a second
preparation without overwriting the saved fixture. PowerShell parser checks pass.
These checks use ordinary packaged mods, with no audit classes added to them.

Startup still reports the prior provider issues: client-class probing, MTS default
config messages, MTR logging-provider messages and incomplete liquor tags/recipes.
WorldEdit's CopyFoxies block-state enumeration delays actual command readiness
well beyond Minecraft's `Done` line (about 152 seconds total on the first complete
run). This is successful fixture startup, not a clean full-pack release result.
Two earlier local launches were interrupted while fixing the terminal's capture
path/stdin setup; their logs are retained and are not successful shutdown evidence.

The final restart loads all five NPCs with their original IDs and saved traits;
`npc list`, `interactions status` and `list` respond after provider initialization.
Exact package, source and retained startup/restart log hashes are recorded in
[ordinary-client-validation-2026-10-22.json](ordinary-client-validation-2026-10-22.json).
The existing `interactions status` count includes numeric NPC bindings only;
the load log correctly reports both name-bound triggers. Physical results remain
pending, and the current fixture remains running for the user's acceptance run.
