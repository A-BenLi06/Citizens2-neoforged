param(
    [string]$GuardJar = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/selected-integrations-2026-10-12/guardvillagers-2.4.12-1.21.1.jar')
)
$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/guard-audit-server'
New-Item -ItemType Directory -Force (Join-Path $fixture 'mods') | Out-Null
Copy-Item -LiteralPath $GuardJar -Destination (Join-Path $fixture 'mods/guardvillagers.jar')
@('server-ip=127.0.0.1','server-port=25630','online-mode=false','level-name=guard-world',
  'level-type=minecraft:flat','generate-structures=false','spawn-protection=0',
  'view-distance=2','simulation-distance=2',
  'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}') |
    Set-Content -LiteralPath (Join-Path $fixture 'server.properties') -Encoding utf8
'eula=true' | Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8
'citizens-guard-audit' | Set-Content -LiteralPath (Join-Path $fixture 'guard-audit-fixture.txt') -Encoding utf8
Write-Output "Prepared $fixture"
