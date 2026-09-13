$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/removal-audit-server'
New-Item -ItemType Directory -Force -Path $fixture | Out-Null
$properties = Join-Path $fixture 'server.properties'
$scope = @{ 'server-ip' = '127.0.0.1'; 'server-port' = '25581'; 'level-name' = 'removal-world' }
if (Test-Path -LiteralPath $properties) {
    $lines = Get-Content -LiteralPath $properties
    foreach ($key in $scope.Keys) {
        $matching = @($lines | Where-Object { $_.StartsWith($key + '=') })
        if ($matching.Count -ne 1 -or $matching[0].Substring($key.Length + 1) -ne $scope[$key]) {
            throw "The destructive audit requires $key=$($scope[$key]) in its isolated fixture"
        }
    }
} else {
    Set-Content -LiteralPath $properties -Encoding utf8 -Value @(
        'server-ip=127.0.0.1', 'server-port=25581', 'online-mode=false', 'level-name=removal-world',
        'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=2', 'simulation-distance=2'
    )
}
Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Value 'eula=true' -Encoding utf8
Set-Content -LiteralPath (Join-Path $fixture 'removal-audit-fixture.txt') -Encoding utf8 -Value 'Dedicated isolated NPC removal audit. Do not copy production NPC data here.'
Write-Output "Prepared isolated removal fixture at $fixture"
