param(
    [string]$SupportDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/ecosystem-2026-10-13/server-smoke/mods'),
    [string]$CreateJar = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/create-provider-pinned.jar'),
    [string]$EconomyJar = (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'yuuniverse-economy/build/libs/yuuniverse-economy-0.5.6-SNAPSHOT.jar'),
    [string]$FixtureDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/logging-audit-server'),
    [ValidateRange(1024,65535)][int]$ServerPort = 25632,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$WorldName = 'logging-world'
)
$ErrorActionPreference = 'Stop'
$fixture = [IO.Path]::GetFullPath($FixtureDirectory)
$fixtureMarker = Join-Path $fixture 'logging-audit-fixture.txt'
if (Test-Path -LiteralPath $fixture) {
    if (!(Test-Path -LiteralPath $fixtureMarker) -or
            (Get-Content -LiteralPath $fixtureMarker -Raw).Trim() -ne 'citizens-logging-audit') {
        throw 'Refusing to prepare an existing directory without the logging fixture marker.'
    }
    $existingWorld = @(Select-String -LiteralPath (Join-Path $fixture 'server.properties') -Pattern '^level-name=(.*)$')
    if ($existingWorld.Count -ne 1 -or $existingWorld[0].Matches[0].Groups[1].Value.Trim() -ne $WorldName) {
        throw 'Existing logging fixture world differs from the requested world; use a separate fixture directory.'
    }
    $sessionFile = Join-Path $fixture "$WorldName/session.lock"
    if (Test-Path -LiteralPath $sessionFile) {
        $sessionGuard = [System.IO.File]::Open($sessionFile, [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read, [System.IO.FileShare]::None)
        $sessionGuard.Dispose()
    }
}
$supportNames = @('architectury-13.0.11-neoforge.jar','neoprotect-1.0.4-mc1.21.1-neoforge.jar',
    'grieflogger-1.2.10-1.21.1-yuuniverse.1-neoforge.jar','supermartijn642configlib-1.1.8-neoforge-mc1.21.jar')
foreach ($providerPath in @($CreateJar, $EconomyJar) + @($supportNames | ForEach-Object { Join-Path $SupportDirectory $_ })) {
    if (!(Test-Path -LiteralPath $providerPath -PathType Leaf)) { throw "Missing logging provider: $providerPath" }
}
New-Item -ItemType Directory -Force (Join-Path $fixture 'mods'), (Join-Path $fixture 'compile-libs') | Out-Null
foreach ($name in $supportNames) {
    Copy-Item -LiteralPath (Join-Path $SupportDirectory $name) -Destination (Join-Path $fixture 'mods')
}
Copy-Item -LiteralPath $EconomyJar -Destination (Join-Path $fixture 'mods/economy.jar')
Copy-Item -LiteralPath $CreateJar -Destination (Join-Path $fixture 'mods/create.jar')
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($provider in @($CreateJar,$EconomyJar)) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead((Get-Item -LiteralPath $provider).FullName)
    try {
        foreach ($entry in $archive.Entries | Where-Object { $_.FullName -like 'META-INF/jarjar/*.jar' }) {
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $fixture "compile-libs/$($entry.Name)"), $true)
        }
    } finally { $archive.Dispose() }
}
@('server-ip=127.0.0.1',"server-port=$ServerPort",'online-mode=false',"level-name=$WorldName",
  'level-type=minecraft:flat','generate-structures=false','spawn-protection=0','view-distance=2','simulation-distance=2',
  'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}') |
    Set-Content -LiteralPath (Join-Path $fixture 'server.properties') -Encoding utf8
'eula=true' | Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8
'citizens-logging-audit' | Set-Content -LiteralPath (Join-Path $fixture 'logging-audit-fixture.txt') -Encoding utf8
Write-Output "Prepared $fixture"
