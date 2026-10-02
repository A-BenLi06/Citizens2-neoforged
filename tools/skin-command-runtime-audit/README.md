# Skin command audit

Run from the repository root with PowerShell 7 and JDK 21:

~~~powershell
./tools/prepare-skin-command-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/skin-command-runtime-audit.gradle runServer --console=plain
~~~

Omit the version override for default 21.1.233. The fixture uses `artifacts/skin-command-audit-server`, loopback port 25628, world `skin-command-world` and marker `skin-command-audit-fixture.txt`. It requires an empty default NPC registry and releases its NPCs, player, permission grant, forced chunk, HTTP server and proxy selector before shutdown. Generated PNGs remain inside the isolated fixture.

Success requires `[SKINCOMMANDAUDIT] COMPLETE 127 checks`, no failure marker and successful Gradle completion. The Java HTTP server binds an ephemeral loopback port. Its responses exercise the actual production URL/multipart/JSON client. A JVM-local proxy answers only `textures.minecraft.net` export requests; native Java-profile cache futures control name lookups. No production URL allowlist or service implementation is bypassed by a test-only production flag.

Coverage includes strict validation before trait attachment, query/direct/clear/URL modes, cached identity, native profile refresh, world/virtual skull components, full-inventory drops, UTF-8 URL payloads and configured authentication, multipart/slim bytes, saved imported textures, invalid/oversized PNGs without upload, HTTP/malformed service errors, stale imports after direct choices/clear/replacement/permission loss/destruction, named lookup ordering, cache reset, saved-data reload, latest toggles during initial lookup, full PNG exports above 10 KB, preserved files after redirects, foreign-host rejection and Bedrock gamertag/unsigned-XUID handling.

The initial fixture passed 112 checks. An expanded reload assertion incorrectly rejected a fresh lookup of the same loaded name; the final fixture instead verifies that a different persisted texture wins over a previous request. The 127-check fixture includes cache-generation and first-lookup mode-toggle cases. Related validation uses skin-refresh/mirroring, creation, scoreboard, mounted-transfer, removal and general runtime fixtures, full Interactions tests and ordinary clean test/build. Run Gradle/server invocations sequentially and verify audit classes remain absent from release/source jars. Evidence: `artifacts/skin-command-validation-summary.json`.
