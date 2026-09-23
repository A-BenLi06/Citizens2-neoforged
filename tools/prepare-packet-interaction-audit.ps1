$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/packet-interaction-audit-server'
New-Item -ItemType Directory -Force -Path $fixture | Out-Null
$properties = Join-Path $fixture 'server.properties'
if (Test-Path -LiteralPath $properties) {
    $lines = Get-Content -LiteralPath $properties
    foreach ($value in @('server-ip=127.0.0.1', 'server-port=25595', 'level-name=packet-interaction-world', 'view-distance=6', 'simulation-distance=3')) {
        if ($value -notin $lines) { throw "Packet interaction audit requires $value" }
    }
} else {
    Set-Content -LiteralPath $properties -Encoding utf8 -Value @(
        'server-ip=127.0.0.1', 'server-port=25595', 'level-name=packet-interaction-world', 'online-mode=false',
        'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=6', 'simulation-distance=3',
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    )
}
Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8 -Value 'eula=true'
Set-Content -LiteralPath (Join-Path $fixture 'packet-interaction-audit-fixture.txt') -Encoding utf8 -Value 'Isolated packet interaction audit. No production NPC/player data belongs here.'
Write-Output "Prepared $fixture"
