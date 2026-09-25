param([Parameter(Mandatory)][string]$LibraryDirectory)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$fixture = Join-Path $root 'artifacts/text-parser-release-server'
$libraries = (Resolve-Path -LiteralPath $LibraryDirectory).Path.Replace('\', '/')
$originalArgs = Join-Path $libraries 'net/neoforged/neoforge/21.1.248/win_args.txt'
$lines = Get-Content -LiteralPath $originalArgs
if ('--fml.neoForgeVersion 21.1.248' -notin $lines -or 'cpw.mods.bootstraplauncher.BootstrapLauncher' -notin $lines) {
    throw 'Expected the installed NeoForge 21.1.248 server launch arguments'
}
New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'mods') | Out-Null
$expected = @('citizens-2.0.43-neoforge-SNAPSHOT.jar', 'yuuniverse-interactions-2.0.43-neoforge-SNAPSHOT.jar')
foreach ($file in Get-ChildItem (Join-Path $fixture 'mods') -File) {
    if ($file.Name -notin $expected) { throw "Unexpected fixture mod: $($file.Name)" }
}
foreach ($name in $expected) {
    Copy-Item -LiteralPath (Join-Path $root "neoforge/build/libs/$name") -Destination (Join-Path $fixture "mods/$name")
}
$properties = Join-Path $fixture 'server.properties'
if (Test-Path -LiteralPath $properties) {
    $existing = Get-Content -LiteralPath $properties
    foreach ($value in @('server-ip=127.0.0.1', 'server-port=25608', 'level-name=text-parser-release-world')) {
        if ($value -notin $existing) { throw "Release smoke fixture requires $value" }
    }
} else {
    Set-Content -LiteralPath $properties -Encoding utf8 -Value @(
        'server-ip=127.0.0.1', 'server-port=25608', 'level-name=text-parser-release-world', 'online-mode=false',
        'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'view-distance=3', 'simulation-distance=3',
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}'
    )
}
# Only libraries are referenced externally; game directory, mods, configuration and saves belong to this fixture.
$rewritten = foreach ($line in $lines) {
    if ($line -eq '-DlibraryDirectory=libraries') {
        '"-DlibraryDirectory=' + $libraries + '"'
    } elseif ($line.StartsWith('-p ')) {
        '-p "' + $line.Substring(3).Replace('libraries/', "$libraries/") + '"'
    } elseif ($line.Contains('libraries/')) {
        '"' + $line.Replace('libraries/', "$libraries/") + '"'
    } else {
        $line
    }
}
Set-Content -LiteralPath (Join-Path $fixture 'launch-args.txt') -Encoding utf8NoBOM -Value $rewritten
Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding ascii -Value 'eula=true'
Set-Content -LiteralPath (Join-Path $fixture 'commands.txt') -Encoding utf8NoBOM -Value @(
    'npc create <gradient:red:blue>PackageParser</gradient> --type COW --at 0,-60,0',
    'npc list',
    'stop'
)
Get-FileHash -LiteralPath $originalArgs -Algorithm SHA256 | ConvertTo-Json | Set-Content (Join-Path $fixture 'library-provenance.json')
Write-Output "Prepared release-only fixture $fixture"
