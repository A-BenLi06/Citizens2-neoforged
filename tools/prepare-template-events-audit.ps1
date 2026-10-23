param([Parameter(Mandatory = $true)][string] $Name)
$ErrorActionPreference = 'Stop'
if ($Name -notmatch '^[a-z0-9][a-z0-9-]+$') { throw 'Use a simple unique artifact directory name' }
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) "artifacts/$Name"
if (Test-Path -LiteralPath $fixture) { throw 'Preserve existing fixture; choose a new name' }
if (Get-NetTCPConnection -State Listen -LocalPort 25644 -ErrorAction SilentlyContinue) { throw 'Fixture port is occupied' }
New-Item -ItemType Directory -Path $fixture | Out-Null
Set-Content -LiteralPath (Join-Path $fixture 'server.properties') -Encoding utf8 -Value @(
    'server-ip=127.0.0.1', 'server-port=25644', 'level-name=template-events-world', 'online-mode=false',
    'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=3', 'simulation-distance=3',
    'enable-rcon=false', 'enable-query=false',
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
)
Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8 -Value 'eula=true'
Set-Content -LiteralPath (Join-Path $fixture 'template-events-fixture.txt') -Encoding utf8 -Value 'Isolated Citizens core template event audit.'
Set-Content -LiteralPath (Join-Path $fixture 'phase.txt') -Encoding utf8 -Value 'seed'
Write-Output "Prepared $fixture"
