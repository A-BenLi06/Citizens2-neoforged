# Dialogue boss bars and private holograms

Implementation follow-up for 2026-09-14 (UTC+08:00), after `9a4c01e`.

## Original behavior checked

The installed Interactions 2.14.1 bytecode and its bundled/current configuration were inspected. The active configuration enables `boss_bar` with BLUE / SEGMENTED_10 and `change_progress_with_time: false`. Both bundled and active configurations use those defaults. Active message overrides are `%name%` while speaking and `&e%name%` while choosing; the bundled English defaults are different, so the port reads both message keys rather than embedding the active server's text.

With timed progress enabled, the original bar starts at zero and fills once per second. Waiting for options sets progress to one and changes the title. Manual-duration lines do not have a finite progress duration. The port uses the session tick clock and clamps progress to valid values instead of creating a separate asynchronous bar task.

The old data contains 41 enabled `hologram_dialogues` sections. All use vertical offset 2.7 and horizontal offset zero. The original renderer strips `%next%` and `{centered}`, displays the dialogue body alongside normal chat, anchors to the conversation's starting location, adjusts the top by `(line count - 1) * 0.2`, and only recreates the hologram when its text changes. Its optional HolographicDisplays/DecentHolograms adapters were inspected for viewer visibility. Configuration counts alone do not prove what an original client rendered.

## Implementation

- Added the full legacy `boss_bar` setting and message overrides, including Bukkit SOLID / SEGMENTED_* style names, enabled state, color and progress mode. Existing constructors remain available. Invalid configuration retains the previous snapshot.
- Each session owns a private `ServerBossEvent`. Titles change between speaking/options, progress follows the original second boundaries, and closing the session removes only its own bar.
- Added typed per-conversation hologram settings. Dialogue bodies use virtual invisible marker armor stands tracked through the existing Citizens packet tracker. They are sent only to the session viewer and never enter a world or NPC registry. Late joiners receive none of their spawn packets.
- Floating text retains chat delivery, resolves player placeholders, removes dialogue controls, preserves empty-row spacing and accepts the port's parsed JSON components. The last displayed body remains visible while choosing options; redraw does not recreate unchanged text or rerun actions.
- Preserved the original starting-location and horizontal-offset calculation. Coincident viewer/anchor positions use viewer yaw to avoid nonfinite coordinates. Native nameplate placement accounts for vanilla's half-block attachment offset.
- Dialogue end, render failure, range/dimension exit, logout, reload and shutdown all release display state. On NeoForge respawn, the session rebinds to the replacement `ServerPlayer` with the same UUID, relinks its bar and resends the existing private entities before continuing. Out-of-range replacements are still rejected before display is sent.

Boss bars and holograms are independent. Neither implements dialogue typewriter text, ActionBar presentation, inline options or interrupt-action lists; those remain separate gaps. CmdCam still owns scripted camera playback.

## Verification and limits

The dedicated fixture passes **45 runtime checks**, including outbound packet recipient inspection and actual player respawn. Eight unit cases cover parsing, message overrides, timed progress and offset geometry. Commands and exact fixture scope are in [the audit README](../tools/dialogue-display-runtime-audit/README.md).

Final validation passes 45 dedicated display checks, 107 existing NPC scenario assertions, 223 runtime probes, 44 removal checks, 48 dialogue unit cases and 148 ordinary tests. Unit failures/errors/skips are zero. The normal build succeeds and its jars exclude all runtime probes. `artifacts/dialogue-display-validation-summary.json` records the log paths and jar hashes. The dedicated acceptance log is `artifacts/repair-dialogue-displays-respawn248.log`.

The native implementation provides the configured feature without depending on Bukkit-only hologram plugins. Exact physical-client typography/provider rendering and complete modpack presentation remain acceptance work; packet/state checks are not screenshot evidence. Production jars and Interactions configuration have not been deployed or migrated by this change.
