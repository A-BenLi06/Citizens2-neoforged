$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/dialogue-display-audit-server'
New-Item -ItemType Directory -Force -Path $fixture | Out-Null
$properties = Join-Path $fixture 'server.properties'
if (Test-Path -LiteralPath $properties) {
    $lines = Get-Content -LiteralPath $properties
    foreach ($entry in @('server-ip=127.0.0.1', 'server-port=25584', 'level-name=display-world')) {
        if ($entry -notin $lines) { throw "Display audit fixture requires $entry" }
    }
} else {
    Set-Content -LiteralPath $properties -Encoding utf8 -Value @(
        'server-ip=127.0.0.1', 'server-port=25584', 'level-name=display-world', 'online-mode=false',
        'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=2', 'simulation-distance=2',
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    )
}
Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8 -Value 'eula=true'
Set-Content -LiteralPath (Join-Path $fixture 'dialogue-display-audit-fixture.txt') -Encoding utf8 -Value 'Isolated dialogue presentation audit. Do not copy production NPC/player data here.'
$worldPack = Join-Path $fixture 'display-world/datapacks/dialogue-world-audit'
New-Item -ItemType Directory -Force -Path $worldPack | Out-Null
Set-Content -LiteralPath (Join-Path $worldPack 'pack.mcmeta') -Encoding utf8 -Value '{"pack":{"pack_format":48,"description":"Isolated dialogue world-resolution fixture"}}'
foreach ($worldNamespace in @('auditworlds', 'otherworlds')) {
    $worldDefinitions = Join-Path $worldPack "data/$worldNamespace/dimension"
    New-Item -ItemType Directory -Force -Path $worldDefinitions | Out-Null
    Set-Content -LiteralPath (Join-Path $worldDefinitions 'island.json') -Encoding utf8 -Value '{"type":"minecraft:overworld","generator":{"type":"minecraft:flat","settings":{"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"structure_overrides":[]}}}'
}
Write-Output "Prepared $fixture"
