$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/menu-collection-audit-server'
New-Item -ItemType Directory -Force -Path $fixture | Out-Null
$properties = Join-Path $fixture 'server.properties'
if (Test-Path -LiteralPath $properties) {
    $lines = Get-Content -LiteralPath $properties
    foreach ($value in @('server-ip=127.0.0.1', 'server-port=25603', 'level-name=menu-collection-world')) {
        if ($value -notin $lines) { throw "Menu collection audit requires $value" }
    }
} else {
    Set-Content -LiteralPath $properties -Encoding utf8 -Value @(
        'server-ip=127.0.0.1', 'server-port=25603', 'level-name=menu-collection-world', 'online-mode=false',
        'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=3', 'simulation-distance=3',
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    )
}
Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8 -Value 'eula=true'
Set-Content -LiteralPath (Join-Path $fixture 'menu-collection-audit-fixture.txt') -Encoding utf8 -Value 'Isolated menu collection audit. No production data belongs here.'
Write-Output "Prepared $fixture"
