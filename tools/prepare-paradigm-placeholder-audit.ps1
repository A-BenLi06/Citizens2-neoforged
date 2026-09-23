param([Parameter(Mandatory = $true)][string]$ParadigmJar)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$provider = Get-Item -LiteralPath $ParadigmJar
if ($provider.Extension -ne '.jar') { throw 'ParadigmJar must be a jar file' }
foreach ($withProvider in @($true, $false)) {
    $name = if ($withProvider) { 'paradigm-placeholder-audit-server' } else { 'paradigm-placeholder-fallback-server' }
    $port = if ($withProvider) { 25598 } else { 25599 }
    $fixture = Join-Path $project "artifacts/$name"
    New-Item -ItemType Directory -Force -Path $fixture, (Join-Path $fixture 'mods') | Out-Null
    $properties = Join-Path $fixture 'server.properties'
    $scope = @('server-ip=127.0.0.1', "server-port=$port", 'level-name=placeholder-world', 'view-distance=6', 'simulation-distance=3')
    if (Test-Path -LiteralPath $properties) {
        $lines = Get-Content -LiteralPath $properties
        foreach ($value in $scope) { if ($value -notin $lines) { throw "The isolated placeholder fixture requires $value" } }
    } else {
        Set-Content -LiteralPath $properties -Encoding utf8 -Value ($scope + @(
            'online-mode=false', 'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0',
            'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
        ))
    }
    Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8 -Value 'eula=true'
    Set-Content -LiteralPath (Join-Path $fixture 'expect-provider.txt') -Encoding utf8 -Value $withProvider.ToString().ToLowerInvariant()
    Set-Content -LiteralPath (Join-Path $fixture 'paradigm-placeholder-audit-fixture.txt') -Encoding utf8 -Value 'Isolated Citizens placeholder audit. No production NPC/player data belongs here.'
    if ($withProvider) {
        Copy-Item -LiteralPath $provider.FullName -Destination (Join-Path $fixture 'mods/paradigm.jar')
        New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'config/paradigm') | Out-Null
        $config = @{}
        foreach ($key in @('telemetryEnable', 'announcementsEnable', 'motdEnable', 'restartEnable', 'commandManagerEnable')) { $config[$key] = @{ value = $false } }
        Set-Content -LiteralPath (Join-Path $fixture 'config/paradigm/main.json') -Encoding utf8 -Value ($config | ConvertTo-Json)
        Set-Content -LiteralPath (Join-Path $fixture 'config/paradigm/dashboard.json') -Encoding utf8 -Value '{"enabled":false,"host":"127.0.0.1","allowRemoteAccess":false}'
        Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $fixture 'mods/paradigm.jar')
    } elseif (Test-Path -LiteralPath (Join-Path $fixture 'mods/paradigm.jar')) {
        throw 'Fallback fixture must not contain Paradigm'
    }
    Write-Output "Prepared $fixture"
}
