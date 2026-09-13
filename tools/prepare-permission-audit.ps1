param([Parameter(Mandatory = $true)][string]$ParadigmJar)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$provider = Get-Item -LiteralPath $ParadigmJar
if ($provider.Extension -ne '.jar') { throw 'ParadigmJar must be a jar file' }
foreach ($withProvider in @($true, $false)) {
    $name = if ($withProvider) { 'permission-audit-server' } else { 'permission-fallback-audit-server' }
    $port = if ($withProvider) { 25582 } else { 25583 }
    $fixture = Join-Path $project "artifacts/$name"
    New-Item -ItemType Directory -Force -Path $fixture, (Join-Path $fixture 'config'),
        (Join-Path $fixture 'mods'), (Join-Path $fixture 'defaultconfigs') | Out-Null
    $properties = Join-Path $fixture 'server.properties'
    $scope = @{ 'server-ip' = '127.0.0.1'; 'server-port' = "$port"; 'level-name' = 'permission-world' }
    if (Test-Path -LiteralPath $properties) {
        $lines = Get-Content -LiteralPath $properties
        foreach ($key in $scope.Keys) {
            $matching = @($lines | Where-Object { $_.StartsWith($key + '=') })
            if ($matching.Count -ne 1 -or $matching[0].Substring($key.Length + 1) -ne $scope[$key]) {
                throw "The permission audit requires $key=$($scope[$key]) in its isolated fixture"
            }
        }
    } else {
        Set-Content -LiteralPath $properties -Encoding utf8 -Value @(
            'server-ip=127.0.0.1', "server-port=$port", 'online-mode=false', 'level-name=permission-world',
            'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=2', 'simulation-distance=2'
        )
    }
    Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8 -Value 'eula=true'
    Set-Content -LiteralPath (Join-Path $fixture 'permission-audit-fixture.txt') -Encoding utf8 -Value 'Isolated Citizens permission audit. No production player, NPC or permission data belongs here.'
    if ($withProvider) {
        Copy-Item -LiteralPath $provider.FullName -Destination (Join-Path $fixture 'mods/paradigm.jar')
        New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'config/paradigm') | Out-Null
        $config = @{}
        foreach ($key in @('internalPermissionsEnable', 'registerForgePermissionHandler')) { $config[$key] = @{ value = $true } }
        foreach ($key in @('telemetryEnable', 'announcementsEnable', 'motdEnable', 'restartEnable', 'commandManagerEnable')) { $config[$key] = @{ value = $false } }
        Set-Content -LiteralPath (Join-Path $fixture 'config/paradigm/main.json') -Encoding utf8 -Value ($config | ConvertTo-Json)
        Set-Content -LiteralPath (Join-Path $fixture 'config/paradigm/dashboard.json') -Encoding utf8 -Value '{"enabled":false,"host":"127.0.0.1","allowRemoteAccess":false}'
        Set-Content -LiteralPath (Join-Path $fixture 'config/neoforge-server.toml') -Encoding utf8 -Value 'permissionHandler = "paradigm:internal"'
        Set-Content -LiteralPath (Join-Path $fixture 'defaultconfigs/neoforge-server.toml') -Encoding utf8 -Value 'permissionHandler = "paradigm:internal"'
        Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $fixture 'mods/paradigm.jar')
    }
    Write-Output "Prepared $fixture"
}
