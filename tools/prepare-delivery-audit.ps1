param([string]$ProviderDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/selected-integrations-2026-10-12'))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$fixture = Join-Path $root 'artifacts/delivery-audit-server'
if (Test-Path -LiteralPath $fixture) {
    if (!(Test-Path -LiteralPath (Join-Path $fixture 'delivery-audit-fixture.txt')) -or
        (Get-Content -LiteralPath (Join-Path $fixture 'delivery-audit-fixture.txt') -Raw).Trim() -ne 'citizens-delivery-audit') {
        throw 'Existing directory is not the delivery fixture'
    }
    $session = Join-Path $fixture 'delivery-world/session.lock'
    if (Test-Path -LiteralPath $session) {
        $guard = [IO.File]::Open($session, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
        $guard.Dispose()
    }
}
New-Item -ItemType Directory -Force (Join-Path $fixture 'mods'), (Join-Path $fixture 'config/ftbquests/quests/chapters') | Out-Null
foreach ($name in @('ftb-library-neoforge-2101.1.37.jar','ftb-quests-neoforge-2101.1.36.jar','ftb-teams-neoforge-2101.1.11.jar')) {
    Copy-Item -LiteralPath (Join-Path $ProviderDirectory $name) -Destination (Join-Path $fixture 'mods')
}
Copy-Item -LiteralPath (Join-Path $root 'artifacts/ecosystem-2026-10-13/server-smoke/mods/architectury-13.0.11-neoforge.jar') -Destination (Join-Path $fixture 'mods')
@('server-ip=127.0.0.1','server-port=25641','online-mode=false','level-name=delivery-world',
  'level-type=minecraft:flat','generate-structures=false','spawn-protection=0','view-distance=2','simulation-distance=2',
  'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}') |
    Set-Content -LiteralPath (Join-Path $fixture 'server.properties') -Encoding utf8NoBOM
'eula=true' | Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding ascii
'citizens-delivery-audit' | Set-Content -LiteralPath (Join-Path $fixture 'delivery-audit-fixture.txt') -Encoding ascii
@'
{
    filename: "delivery_audit"
    id: "00000000004D1001"
    quests: [
        { id: "00000000004D1002", tasks: [{id: "00000000004D1003", type: "item", item: {count: 1, id: "minecraft:paper"}, count: 5L, consume_items: true}], x: 0.0d, y: 0.0d }
        { id: "00000000004D1012", tasks: [{id: "00000000004D1013", type: "item", item: {count: 1, id: "minecraft:paper"}, count: 5L, consume_items: true}], x: 2.0d, y: 0.0d }
    ]
}
'@ | Set-Content -LiteralPath (Join-Path $fixture 'config/ftbquests/quests/chapters/delivery_audit.snbt') -Encoding utf8NoBOM
Write-Output "Prepared $fixture"
