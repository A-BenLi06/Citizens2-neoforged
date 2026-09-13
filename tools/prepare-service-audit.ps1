param(
    [Parameter(Mandatory = $true)][string]$EconomyJar,
    [Parameter(Mandatory = $true)][string]$CmdCamJar,
    [Parameter(Mandatory = $true)][string]$CreativeCoreJar,
    [Parameter(Mandatory = $true)][string]$LegacyWorld
)
$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/service-audit-server'
New-Item -ItemType Directory -Force (Join-Path $fixture 'mods'), (Join-Path $fixture 'world/data'),
    (Join-Path $fixture 'config/yuuniverse_economy'), (Join-Path $fixture 'config/interactions') | Out-Null
$providers = @{ economy = $EconomyJar; cmdcam = $CmdCamJar; creativecore = $CreativeCoreJar }
foreach ($name in $providers.Keys) {
    $source = Get-Item -LiteralPath $providers[$name]
    if ($source.Extension -ne '.jar') { throw "$name must be a jar file" }
    Copy-Item -LiteralPath $source.FullName -Destination (Join-Path $fixture "mods/$name.jar")
}
$legacyRoot = (Get-Item -LiteralPath $LegacyWorld).FullName
$sceneFiles = @(Get-ChildItem -LiteralPath $legacyRoot -Filter 'cmdcam_Scenes.dat' -File -Recurse)
if ($sceneFiles.Count -eq 0) { throw 'The supplied legacy world contains no CmdCam scenes' }
foreach ($scene in $sceneFiles) {
    $relative = [System.IO.Path]::GetRelativePath($legacyRoot, $scene.FullName)
    $destination = Join-Path (Join-Path $fixture 'world') $relative
    New-Item -ItemType Directory -Force (Split-Path $destination -Parent) | Out-Null
    Copy-Item -LiteralPath $scene.FullName -Destination $destination
}
$files = @{
    'eula.txt' = 'eula=true'
    'server.properties' = "server-ip=127.0.0.1`nserver-port=25580`nonline-mode=false`nlevel-name=world`nlevel-type=minecraft:flat`ngenerate-structures=false`nspawn-protection=0`nview-distance=2`nsimulation-distance=2"
    'config/yuuniverse_economy/currencies.json' = '{"schemaVersion":1,"currencies":[{"id":"audit","displayName":"Audit","symbol":"A","scale":2,"defaultCurrency":true,"payable":true,"exchangeRate":"1"},{"id":"reserve","displayName":"储备币","symbol":"R","scale":3,"defaultCurrency":false,"payable":true,"exchangeRate":"1"}]}'
    'config/interactions/command-aliases.yml' = "shop: ''`ncam-server: ''`nquestadmin: ''`n"
}
foreach ($name in $files.Keys) {
    $target = Join-Path $fixture $name
    if ($name -eq 'config/yuuniverse_economy/currencies.json' -or !(Test-Path -LiteralPath $target)) {
        Set-Content -LiteralPath $target -Value $files[$name] -Encoding utf8
    }
}
Write-Output "Prepared isolated service fixture at $fixture"
