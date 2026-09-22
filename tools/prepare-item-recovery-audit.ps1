$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/item-recovery-audit-server'
New-Item -ItemType Directory -Force -Path $fixture | Out-Null
$properties = Join-Path $fixture 'server.properties'
$scope = @('server-ip=127.0.0.1', 'server-port=25589', 'level-name=recovery-world')
if (Test-Path -LiteralPath $properties) {
    $lines = Get-Content -LiteralPath $properties
    foreach ($entry in $scope) { if ($entry -notin $lines) { throw "Item recovery fixture requires $entry" } }
} else {
    Set-Content -LiteralPath $properties -Encoding utf8 -Value ($scope + @(
        'online-mode=false', 'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0',
        'view-distance=2', 'simulation-distance=2',
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    ))
}
Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Value 'eula=true' -Encoding utf8
Set-Content -LiteralPath (Join-Path $fixture 'item-recovery-audit-fixture.txt') -Value 'Isolated item recovery fixture; never add production NPC data' -Encoding utf8
Write-Output "Prepared $fixture"
