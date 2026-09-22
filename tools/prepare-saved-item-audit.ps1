param(
    [Parameter(Mandatory = $true)][string[]]$ProviderJars,
    [Parameter(Mandatory = $true)][string]$SavedItemDatabase,
    [Parameter(Mandatory = $true)][string]$ExpectedPayloads
)
$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/saved-item-audit-server'
New-Item -ItemType Directory -Force (Join-Path $fixture 'mods'), (Join-Path $fixture 'config/interactions') | Out-Null
foreach ($path in $ProviderJars) {
    $provider = Get-Item -LiteralPath $path
    if ($provider.Extension -ne '.jar') { throw 'Provider must be a jar' }
    Copy-Item -LiteralPath $provider.FullName -Destination (Join-Path $fixture ('mods/' + $provider.Name))
}
Copy-Item -LiteralPath $SavedItemDatabase -Destination (Join-Path $fixture 'config/interactions/items.yml')
Copy-Item -LiteralPath $ExpectedPayloads -Destination (Join-Path $fixture 'expected-payloads.json')
$properties = Join-Path $fixture 'server.properties'
if (Test-Path -LiteralPath $properties) {
    $lines = Get-Content -LiteralPath $properties
    foreach ($entry in @('server-ip=127.0.0.1', 'server-port=25588', 'level-name=item-world')) {
        if ($entry -notin $lines) { throw "Saved item fixture requires $entry" }
    }
} else {
    Set-Content -LiteralPath $properties -Encoding utf8 -Value @(
        'server-ip=127.0.0.1', 'server-port=25588', 'level-name=item-world', 'online-mode=false',
        'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=2', 'simulation-distance=2',
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    )
}
Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Value 'eula=true' -Encoding utf8
Set-Content -LiteralPath (Join-Path $fixture 'saved-item-audit-fixture.txt') -Value 'Isolated saved item integration fixture' -Encoding utf8
Write-Output "Prepared $fixture"
