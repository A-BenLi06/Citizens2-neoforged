param(
    [Parameter(Mandatory)][string]$LibraryDirectory,
    [string]$PackageDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/assembly-recovery-release-248-jars-2026-10-22'),
    [string]$SupportDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/ecosystem-2026-10-13/server-smoke/mods'),
    [string]$ProviderDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/selected-integrations-2026-10-12'),
    [string]$EconomyJar = (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'yuuniverse-economy/build/libs/yuuniverse-economy-0.5.6-SNAPSHOT.jar'),
    [ValidatePattern('^[A-Za-z0-9_]{1,16}$')][string]$PlayerName = 'BenLi06',
    [ValidateRange(1024,65535)][int]$Port = 25640
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$fixture = Join-Path $root 'artifacts/ordinary-client-acceptance-server'
if (Test-Path -LiteralPath $fixture) { throw "Refusing to overwrite existing acceptance data: $fixture" }
$libraries = (Resolve-Path -LiteralPath $LibraryDirectory).Path.Replace('\', '/')
$originalArgs = Join-Path $libraries 'net/neoforged/neoforge/21.1.248/win_args.txt'
$lines = Get-Content -LiteralPath $originalArgs
if ('--fml.neoForgeVersion 21.1.248' -notin $lines -or 'cpw.mods.bootstraplauncher.BootstrapLauncher' -notin $lines) {
    throw 'Expected installed NeoForge 21.1.248 server launch arguments'
}
# Take only JARs from the old synthetic stack. No production configs, accounts,
# worlds, databases, scripts, automatic pack synchronization or acceptance hooks.
$excluded = @('[自研]citizens-2.0.43-neoforge-SNAPSHOT.jar', '[自研]yuuniverse-interactions.jar',
    'yuuniverse-economy-0.5.0-SNAPSHOT.jar', 'rhino-2101.2.7-build.85.jar', 'automodpack-5.0.0-rc.2.jar')
$sources = @((Get-ChildItem -LiteralPath $SupportDirectory -File -Filter '*.jar' |
    Where-Object Name -NotIn $excluded).FullName) + @(
    (Join-Path $PackageDirectory 'citizens-2.0.43-neoforge-SNAPSHOT.jar'),
    (Join-Path $PackageDirectory 'yuuniverse-interactions-2.0.43-neoforge-SNAPSHOT.jar'),
    (Join-Path $ProviderDirectory 'rhino-2101.2.8-build.91.jar'), $EconomyJar)
foreach ($path in $sources) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing acceptance artifact: $path" }
}
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port is already in use" }
New-Item -ItemType Directory -Path (Join-Path $fixture 'mods'),
    (Join-Path $fixture 'config/interactions/conversations'), (Join-Path $fixture 'client-overrides'),
    (Join-Path $fixture 'config/ftbquests/quests/chapters') | Out-Null
foreach ($path in $sources) { Copy-Item -LiteralPath $path -Destination (Join-Path $fixture 'mods') }
# Provide exact common-side overrides for a copied client profile; this directory
# is deliberately not described as a complete client modpack.
$clientNames = @('rhino-2101.2.8-build.91.jar', 'ftb-library-neoforge-2101.1.37.jar',
    'ftb-quests-neoforge-2101.1.36.jar', 'ftb-teams-neoforge-2101.1.11.jar',
    'ftb-xmod-compat-neoforge-21.1.12.jar', 'kubejs-neoforge-2101.7.2-build.377.jar',
    'jei-1.21.1-neoforge-19.53.0.425.jar', 'architectury-13.0.11-neoforge.jar',
    'guardvillagers-2.4.12-1.21.1.jar', 'vivecraft-1.21.1-1.3.15-neoforge.jar',
    'CMDCam_NEOFORGE_v2.2.9_mc1.21.1.jar', 'CreativeCore_NEOFORGE_v2.13.50_mc1.21.1.jar',
    '[自修改]create-6.0.10-mc1.21.1-performance-v6.jar')
foreach ($name in $clientNames) {
    Copy-Item -LiteralPath (Join-Path $fixture "mods/$name") -Destination (Join-Path $fixture 'client-overrides')
}
$rewritten = foreach ($line in $lines) {
    if ($line -eq '-DlibraryDirectory=libraries') { '"-DlibraryDirectory=' + $libraries + '"' }
    elseif ($line.StartsWith('-p ')) { '-p "' + $line.Substring(3).Replace('libraries/', "$libraries/") + '"' }
    elseif ($line.Contains('libraries/')) { '"' + $line.Replace('libraries/', "$libraries/") + '"' }
    else { $line }
}
$rewritten | Set-Content -LiteralPath (Join-Path $fixture 'launch-args.txt') -Encoding utf8NoBOM
@('server-ip=127.0.0.1', "server-port=$Port", 'online-mode=false', 'level-name=acceptance-world',
    'level-type=minecraft:flat', 'generate-structures=false', 'spawn-protection=0', 'gamemode=creative',
    'enable-rcon=false', 'enable-query=false', 'view-distance=4', 'simulation-distance=4', 'max-players=4',
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}') |
    Set-Content -LiteralPath (Join-Path $fixture 'server.properties') -Encoding utf8NoBOM
'eula=true' | Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding ascii
# Offline UUID is confined to the loopback fixture. Keep online production identity untouched.
$digest = [System.Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$PlayerName"))
$digest[6] = ($digest[6] -band 0x0f) -bor 0x30
$digest[8] = ($digest[8] -band 0x3f) -bor 0x80
$hex = [Convert]::ToHexString($digest).ToLowerInvariant()
$uuid = '{0}-{1}-{2}-{3}-{4}' -f $hex.Substring(0,8),$hex.Substring(8,4),$hex.Substring(12,4),$hex.Substring(16,4),$hex.Substring(20,12)
ConvertTo-Json -InputObject @(@{uuid=$uuid;name=$PlayerName;level=4;bypassesPlayerLimit=$false}) |
    Set-Content -LiteralPath (Join-Path $fixture 'ops.json') -Encoding utf8NoBOM
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'client-acceptance/conversation.yml') -Destination (Join-Path $fixture 'config/interactions/conversations/client-acceptance.yml')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'client-acceptance/quest.snbt') -Destination (Join-Path $fixture 'config/ftbquests/quests/chapters/client_acceptance.snbt')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'client-acceptance/bootstrap.txt') -Destination (Join-Path $fixture 'bootstrap.txt')
$manifest = [ordered]@{
    schema=1; preparedAt=[DateTimeOffset]::Now.ToString('o'); marker='citizens-ordinary-client-acceptance'
    player=$PlayerName; offlineUuid=$uuid; port=$Port; minecraft='1.21.1'; neoforge='21.1.248'
    sourceCommit=(& git -C $root rev-parse HEAD); libraryArgs=(Get-FileHash -LiteralPath $originalArgs).Hash
    files=@(Get-ChildItem -LiteralPath (Join-Path $fixture 'mods') -File | Sort-Object Name | ForEach-Object {
        @{name=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    })
    clientOverrides=$clientNames
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $fixture 'acceptance-manifest.json') -Encoding utf8NoBOM
Write-Output "Prepared $fixture for $PlayerName at 127.0.0.1:$Port"
