$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/swap-hand-audit-server'
$port = 25621
if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
    throw "Swap-hand audit port $port is already in use"
}
New-Item -ItemType Directory -Force -Path $fixture | Out-Null
$properties = Join-Path $fixture 'server.properties'
if (Test-Path -LiteralPath $properties) {
    $lines = Get-Content -LiteralPath $properties
    foreach ($value in @('server-ip=127.0.0.1', "server-port=$port", 'level-name=swap-hand-world', 'view-distance=6', 'simulation-distance=3')) {
        if ($value -notin $lines) { throw "Swap-hand audit requires $value" }
    }
} else {
    Set-Content -LiteralPath $properties -Encoding utf8 -Value @(
        'server-ip=127.0.0.1', "server-port=$port", 'level-name=swap-hand-world', 'online-mode=false',
        'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=6', 'simulation-distance=3',
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    )
}
Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8 -Value 'eula=true'
Set-Content -LiteralPath (Join-Path $fixture 'swap-hand-audit-fixture.txt') -Encoding utf8 -Value 'Isolated swap-hand audit. No production data belongs here.'
$dialogueConfig = Join-Path $fixture 'config/interactions/config.yml'
New-Item -ItemType Directory -Force -Path (Split-Path $dialogueConfig -Parent) | Out-Null
# The fixture registers its own controller after startup; keep the normal mod's controller inert for swap events.
Set-Content -LiteralPath $dialogueConfig -Encoding utf8 -Value 'conversation_start_click_type: RIGHT_CLICK'
Write-Output "Prepared $fixture"
