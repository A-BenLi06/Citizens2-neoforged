param(
    [string]$ProviderDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/selected-integrations-2026-10-12'),
    [string]$SupportDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/ecosystem-2026-10-13/server-smoke/mods'),
    [string]$EconomyJar = (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'yuuniverse-economy/build/libs/yuuniverse-economy-0.5.8-SNAPSHOT.jar')
)
$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/xmod-audit-server'
New-Item -ItemType Directory -Force (Join-Path $fixture 'mods'), (Join-Path $fixture 'kubejs/server_scripts') | Out-Null
foreach ($name in @('ftb-library-neoforge-2101.1.37.jar','ftb-quests-neoforge-2101.1.36.jar','ftb-teams-neoforge-2101.1.11.jar',
        'ftb-xmod-compat-neoforge-21.1.12.jar','kubejs-neoforge-2101.7.2-build.377.jar','rhino-2101.2.8-build.91.jar')) {
    Copy-Item -LiteralPath (Join-Path $ProviderDirectory $name) -Destination (Join-Path $fixture 'mods')
}
foreach ($name in @('architectury-13.0.11-neoforge.jar','jei-1.21.1-neoforge-19.53.0.425.jar')) {
    Copy-Item -LiteralPath (Join-Path $SupportDirectory $name) -Destination (Join-Path $fixture 'mods')
}
Copy-Item -LiteralPath $EconomyJar -Destination (Join-Path $fixture 'mods/economy.jar')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'xmod-runtime-audit/citizens_xmod_audit.js') -Destination (Join-Path $fixture 'kubejs/server_scripts')
@('server-ip=127.0.0.1','server-port=25631','online-mode=false','level-name=xmod-world',
  'level-type=minecraft:flat','generate-structures=false','spawn-protection=0',
  'view-distance=2','simulation-distance=2',
  'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}') |
    Set-Content -LiteralPath (Join-Path $fixture 'server.properties') -Encoding utf8
'eula=true' | Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8
'citizens-xmod-audit' | Set-Content -LiteralPath (Join-Path $fixture 'xmod-audit-fixture.txt') -Encoding utf8
Write-Output "Prepared $fixture"
