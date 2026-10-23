param(
    [string]$GuardJar = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/selected-integrations-2026-10-12/guardvillagers-2.4.12-1.21.1.jar'),
    [string]$FixtureDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/guard-audit-server')
)
$ErrorActionPreference = 'Stop'
$fixture = [IO.Path]::GetFullPath($FixtureDirectory)
$artifacts = [IO.Path]::GetFullPath((Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts')) + [IO.Path]::DirectorySeparatorChar
if (!$fixture.StartsWith($artifacts,[StringComparison]::OrdinalIgnoreCase)) { throw 'Guard fixture must be inside task artifacts' }
if (Test-Path -LiteralPath $fixture) {
    $marker = Join-Path $fixture 'guard-audit-fixture.txt'
    if (!(Test-Path -LiteralPath $marker) -or (Get-Content -LiteralPath $marker -Raw).Trim() -ne 'citizens-guard-audit') {
        throw 'Refusing an existing directory without the Guard fixture marker'
    }
    $world = @(Select-String -LiteralPath (Join-Path $fixture 'server.properties') -Pattern '^level-name=(.*)$')
    if ($world.Count -ne 1 -or $world[0].Matches[0].Groups[1].Value.Trim() -ne 'guard-world') { throw 'Existing Guard fixture world differs; use a separate fixture' }
    if (Test-Path -LiteralPath (Join-Path $fixture 'guard-world/session.lock')) {
        $guard = [IO.File]::Open((Join-Path $fixture 'guard-world/session.lock'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)
        $guard.Dispose()
    }
    if (Test-Path -LiteralPath (Join-Path $fixture 'guard-restart-state.json')) { throw 'Preserve the completed Guard restart fixture' }
}
$provider = (Get-Item -LiteralPath $GuardJar).FullName
New-Item -ItemType Directory -Force (Join-Path $fixture 'mods') | Out-Null
Copy-Item -LiteralPath $provider -Destination (Join-Path $fixture 'mods/guardvillagers.jar')
@('server-ip=127.0.0.1','server-port=25630','online-mode=false','level-name=guard-world',
  'level-type=minecraft:flat','generate-structures=false','spawn-protection=0',
  'view-distance=2','simulation-distance=2',
  'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}') |
    Set-Content -LiteralPath (Join-Path $fixture 'server.properties') -Encoding utf8
'eula=true' | Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8
'citizens-guard-audit' | Set-Content -LiteralPath (Join-Path $fixture 'guard-audit-fixture.txt') -Encoding utf8
Write-Output "Prepared $fixture"
