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
Paradigm supplies permissions/groups and owned NPC placeholder registration;
CMDCam supplies supported camera playback. FTB quests/crafting and specialist
provider acceptance remain tracked separately in the adaptation backlog.

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
