param(
    [Parameter(Mandatory = $true)][string] $Name,
    [ValidateSet('seed', 'recover')][string] $Phase = 'seed'
)
$ErrorActionPreference = 'Stop'
if ($Name -notmatch '^[a-z0-9][a-z0-9-]+$') { throw 'Use a simple unique artifact directory name' }
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) "artifacts/$Name"
if (Get-NetTCPConnection -State Listen -LocalPort 25646 -ErrorAction SilentlyContinue) { throw 'Fixture port is occupied' }
if ($Phase -eq 'recover') {
    if (!(Test-Path -LiteralPath (Join-Path $fixture 'seed.complete.json')) -or
            (Test-Path -LiteralPath (Join-Path $fixture 'recover.started'))) { throw 'Require a completed seed and fresh recovery' }
    $log = Get-Content -LiteralPath (Join-Path $fixture 'logs/latest.log') -Raw
    if (!$log.Contains('[NAVSETTINGSAUDIT] COMPLETE seed') -or !$log.Contains('All dimensions are saved')) { throw 'Seed must stop normally first' }
    $evidence = Join-Path $fixture 'evidence/seed'
    New-Item -ItemType Directory -Path $evidence | Out-Null
    Copy-Item -LiteralPath (Join-Path $fixture 'config/citizens/saves.yml') -Destination (Join-Path $evidence 'saves.yml')
    Copy-Item -LiteralPath (Join-Path $fixture 'logs/latest.log') -Destination (Join-Path $evidence 'latest.log')
} else {
    if (Test-Path -LiteralPath $fixture) { throw 'Preserve existing fixture; choose a new name' }
    New-Item -ItemType Directory -Path $fixture | Out-Null
    Set-Content -LiteralPath (Join-Path $fixture 'server.properties') -Encoding utf8 -Value @(
        'server-ip=127.0.0.1', 'server-port=25646', 'level-name=navigation-settings-world', 'online-mode=false',
        'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=3', 'simulation-distance=3',
        'enable-rcon=false', 'enable-query=false',
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    )
    Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8 -Value 'eula=true'
    Set-Content -LiteralPath (Join-Path $fixture 'navigation-settings-fixture.txt') -Encoding utf8 -Value 'Isolated Citizens navigation settings audit.'
}
Set-Content -LiteralPath (Join-Path $fixture 'phase.txt') -Encoding utf8 -Value $Phase
Write-Output "Prepared $Phase $fixture"
