# Citizens for NeoForge

Native port of Citizens2 for **Minecraft 1.21.1 and Java 21**, with a separate
Yuuniverse Interactions dialogue mod. The build defaults to NeoForge 21.1.233;
native acceptance also targets 21.1.248. Bukkit plugins and extensions require
native adapters and cannot be loaded as NeoForge mods.

NPC creation, traits, skins, equipment, navigation, waypoints, packet NPCs,
holograms, inventory shops, commands, templates and behavior trees are implemented.
The [current acceptance checklist](docs/remaining-citizens-work-2026-09-26.md)
distinguishes implementation from remaining integration, migration and client
verification. The selected ecosystem and its open completion gates are recorded
in the [adaptation backlog](docs/ecosystem-adaptation-goals-2026-10-13.md).

## Build

From the repository root with PowerShell 7 and a JDK 21 `JAVA_HOME`:

```powershell
./neoforge/gradlew.bat -p neoforge clean test build --console=plain
```

Outputs are in `neoforge/build/libs`:

- `citizens-<version>.jar`: NPC engine and native API.
- `yuuniverse-interactions-<version>.jar`: optional dialogue/cutscene engine;
  requires Citizens.
- `citizens-<version>-sources.jar`: Citizens sources.

Install the binary Citizens JAR in the server's `mods` folder. Add Interactions
when using conversations. They remain separate mods; Interactions depends on
Citizens. Do not install source JARs or opt-in runtime-audit builds.

To build against the other acceptance target:

```powershell
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' test build --console=plain
```

## Integrations

Economy owns currency, settlement and its system/coordinate shop menus. The
[system-menu adapter](docs/economy-menu-adapter-2026-10-16.md) requires the
supported optional API from Economy 0.5.1+, and the
[coordinate adapter](docs/economy-coordinate-adapter-2026-10-16.md) requires 0.5.2+.
Consumers reacquire the provider and validate player/menu targets before opening.
Paradigm supplies permissions/groups, owned NPC placeholder registration and
[configured Economy/FTB exports](docs/paradigm-provider-exports-2026-10-18.md);
CMDCam supplies supported camera playback. [FTB task/delivery integration](docs/ftb-task-adapter-2026-10-18.md)
shares native team state with NPC commands and dialogue. [RSItemEditor references](docs/rsitemeditor-adapter-2026-10-18.md)
resolve shared/personal items and native permissions. [Legacy item migration](docs/legacy-rsitemeditor-migration-2026-10-18.md)
provides preview, source backups and conflict-safe shared imports. Quest rewards/crafting,
missing legacy content and specialist acceptance remain in the adaptation backlog.
[Native recipe discovery](docs/crafting-recipe-discovery-2026-10-18.md) supplies
current server definitions and NPC/dialogue guidance with revision checks; machine
processes, craft evidence and durable quest rewards remain separate acceptance.
[Task discovery](docs/ftb-task-discovery-2026-10-18.md) adds visibility-filtered
listing and details, prerequisites, repeatability and native reward ownership.
[Crafting observations](docs/crafting-evidence-2026-10-18.md) attribute supported
native result takes to the actual recipe holder, actor and complete output batch;
[Crafting outcomes and evidence journal](docs/quest-evidence-journal-2026-10-18.md) add native return status and immutable durable revisions.
[Explicit FTB craft bindings](docs/ftb-craft-evidence-projection-2026-10-18.md) apply
confirmed recipe evidence to owned CustomTasks with stable generations and
authenticated review. Monetary rewards and crash/rollback acceptance remain required.
[Create batch evidence](docs/create-batch-evidence-2026-10-19.md) extends native
machine completion to saw cutting and crushing-wheel crushing/milling, preserving
actual random outputs and remainders. Real JVM termination/restart exercises
durable phases and independently saved world/FTB rollback fences.
[World pressing evidence](docs/create-press-evidence-2026-10-19.md) observes native
mechanical press bulk/single processing with exact entity outputs and remainders.
Native evidence also covers depot/belt pressing, basin mixing/compacting,
spout/drain fluid processing, deploying, fan washing/haunting and
[sequenced assembly completion](docs/create-assembly-evidence-2026-10-19.md).
[Assembly process-kill recovery](docs/create-assembly-recovery-2026-10-20.md)
checks independently saved input, tool/fluid state and quest rollback at durable
admission, confirmation and application. Further recovery and integrated acceptance
remain in the adopted backlog.

[NeoProtect/GriefLogger attribution](docs/logging-attribution-2026-10-19.md)
retains independent native histories, corrects duplicate multi-block placement
records and verifies world rollback against committed Economy receipts. Retention,
hard-kill/load and integrated client acceptance remain in the backlog.

The existing configuration, shop and text-input GUIs use vanilla container,
merchant and anvil protocols. They do not introduce a Citizens-specific client
screen. Actual clients and other content/camera/quest mods have separate
installation and acceptance requirements.

## Upstream

Citizens was created by CitizensDev as a Bukkit NPC plugin. The upstream source
trees remain here as porting references; the native build is under `neoforge`.
Upstream documentation describes Bukkit APIs and may need adaptation:
[wiki](https://wiki.citizensnpcs.co/API),
[Javadoc](https://jd.citizensnpcs.co),
[project](https://github.com/CitizensDev/Citizens2).
