param(
    [Parameter(Mandatory = $true)][string] $Name,
    [ValidateSet('commands', 'interaction-label', 'packet-hologram')][string] $Regression = 'commands'
)
$ErrorActionPreference = 'Stop'
if ($Name -notmatch '^[a-z0-9][a-z0-9-]+$') { throw 'Use a simple unique artifact directory name' }
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) "artifacts/$Name"
if (Test-Path -LiteralPath $fixture) { throw 'Preserve existing fixture; choose a new name' }
if (Get-NetTCPConnection -State Listen -LocalPort 25645 -ErrorAction SilentlyContinue) { throw 'Fixture port is occupied' }
New-Item -ItemType Directory -Path $fixture | Out-Null
Set-Content -LiteralPath (Join-Path $fixture 'server.properties') -Encoding utf8 -Value @(
    'server-ip=127.0.0.1', 'server-port=25645', 'level-name=navigation-command-world', 'online-mode=false',
    'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=6', 'simulation-distance=3',
    'enable-rcon=false', 'enable-query=false',
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
)
Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8 -Value 'eula=true'
Set-Content -LiteralPath (Join-Path $fixture 'navigation-command-fixture.txt') -Encoding utf8 -Value 'Isolated Citizens core movement command audit.'
if ($Regression -ne 'commands') {
    Set-Content -LiteralPath (Join-Path $fixture "$Regression-audit-fixture.txt") -Encoding utf8 -Value 'Fresh navigation regression fixture.'
}
Write-Output "Prepared $fixture"
