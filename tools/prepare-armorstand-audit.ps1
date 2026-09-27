$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/armorstand-audit-server'
New-Item -ItemType Directory -Force -Path $fixture | Out-Null
$properties = Join-Path $fixture 'server.properties'
if (Test-Path -LiteralPath $properties) {
    $lines = Get-Content -LiteralPath $properties
    foreach ($value in @('server-ip=127.0.0.1', 'server-port=25619', 'level-name=armorstand-world')) {
        if ($value -notin $lines) { throw "Armor stand audit requires $value" }
    }
} else {
    Set-Content -LiteralPath $properties -Encoding utf8 -Value @(
        'server-ip=127.0.0.1', 'server-port=25619', 'level-name=armorstand-world', 'online-mode=false',
        'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=3', 'simulation-distance=3',
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    )
}
Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8 -Value 'eula=true'
Set-Content -LiteralPath (Join-Path $fixture 'armorstand-audit-fixture.txt') -Encoding utf8 -Value 'Isolated armorstand audit. No production data belongs here.'
$config = Join-Path $fixture 'config/neoforge-server.toml'
New-Item -ItemType Directory -Force -Path (Split-Path $config -Parent) | Out-Null
if (Test-Path -LiteralPath $config) {
    $contents = Get-Content -LiteralPath $config -Raw
    $contents = $contents -replace 'permissionHandler = "[^"]+"', 'permissionHandler = "citizens:armorstand_audit"'
    Set-Content -LiteralPath $config -Encoding utf8 -Value $contents
} else {
    Set-Content -LiteralPath $config -Encoding utf8 -Value 'permissionHandler = "citizens:armorstand_audit"'
}
Write-Output "Prepared $fixture"
