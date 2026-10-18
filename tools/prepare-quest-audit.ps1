param(
    [string]$ProviderDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/selected-integrations-2026-10-12'),
    [string]$ParadigmJar = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/ecosystem-2026-10-13/server-smoke/mods/Paradigm-neoforge-1.21.1-2.5.0.jar'),
    [string]$ArchitecturyJar = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/ecosystem-2026-10-13/server-smoke/mods/architectury-13.0.11-neoforge.jar')
)
$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/quest-audit-server'
New-Item -ItemType Directory -Force (Join-Path $fixture 'mods') | Out-Null
foreach ($name in @('ftb-library-neoforge-2101.1.37.jar','ftb-quests-neoforge-2101.1.36.jar','ftb-teams-neoforge-2101.1.11.jar','rsitemeditor-1.0.1.jar','kotlinforforge-5.12.0-all.jar')) {
    Copy-Item -LiteralPath (Join-Path $ProviderDirectory $name) -Destination (Join-Path $fixture 'mods')
}
Copy-Item -LiteralPath $ParadigmJar -Destination (Join-Path $fixture 'mods')
Copy-Item -LiteralPath $ArchitecturyJar -Destination (Join-Path $fixture 'mods')
@('server-ip=127.0.0.1','server-port=25628','online-mode=false','level-name=quest-world',
  'level-type=minecraft:flat','generate-structures=false','spawn-protection=0',
  'view-distance=2','simulation-distance=2','sync-chunk-writes=false',
  'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}') |
    Set-Content -LiteralPath (Join-Path $fixture 'server.properties') -Encoding utf8
'eula=true' | Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8
'citizens-quest-audit' | Set-Content -LiteralPath (Join-Path $fixture 'quest-audit-fixture.txt') -Encoding utf8
New-Item -ItemType Directory -Force (Join-Path $fixture 'config'), (Join-Path $fixture 'defaultconfigs') | Out-Null
'permissionHandler = "paradigm:internal"' | Set-Content -LiteralPath (Join-Path $fixture 'config/neoforge-server.toml') -Encoding utf8
'permissionHandler = "paradigm:internal"' | Set-Content -LiteralPath (Join-Path $fixture 'defaultconfigs/neoforge-server.toml') -Encoding utf8
Write-Output "Prepared $fixture"
