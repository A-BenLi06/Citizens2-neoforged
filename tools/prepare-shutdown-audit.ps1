$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/shutdown-audit-server'
New-Item -ItemType Directory -Force -Path $fixture | Out-Null
$properties = Join-Path $fixture 'server.properties'
$scope = @{ 'server-ip' = '127.0.0.1'; 'server-port' = '25640'; 'level-name' = 'shutdown-world' }
if (Test-Path -LiteralPath $properties) {
    $lines = Get-Content -LiteralPath $properties
    foreach ($key in $scope.Keys) {
        $matching = @($lines | Where-Object { $_.StartsWith($key + '=') })
        if ($matching.Count -ne 1 -or $matching[0].Substring($key.Length + 1) -ne $scope[$key]) {
            throw "The shutdown audit requires $key=$($scope[$key]) in its isolated fixture"
        }
    }
} else {
    Set-Content -LiteralPath $properties -Encoding utf8 -Value @(
        'server-ip=127.0.0.1', 'server-port=25640', 'level-name=shutdown-world', 'online-mode=false',
        'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=3', 'simulation-distance=3',
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    )
}
Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8 -Value 'eula=true'
Set-Content -LiteralPath (Join-Path $fixture 'shutdown-audit-fixture.txt') -Encoding utf8 -Value 'Isolated NPC shutdown audit. No production data belongs here.'
Write-Output "Prepared $fixture"
