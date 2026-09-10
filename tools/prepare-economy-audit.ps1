param([Parameter(Mandatory = $true)][string]$EconomyJar)
$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/economy-audit-server'
$jar = Get-Item -LiteralPath $EconomyJar
if ($jar.Extension -ne '.jar') { throw 'EconomyJar must be a jar file' }
New-Item -ItemType Directory -Force (Join-Path $fixture 'mods'), (Join-Path $fixture 'config/yuuniverse_economy') | Out-Null
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $fixture 'mods/economy.jar')
$files = @{
    'eula.txt' = 'eula=true'
    'server.properties' = "server-ip=127.0.0.1`nserver-port=25579`nonline-mode=false`nlevel-name=world`nlevel-type=minecraft:flat`ngenerate-structures=false`nspawn-protection=0`nview-distance=2`nsimulation-distance=2"
    'config/yuuniverse_economy/currencies.json' = '{"schemaVersion":1,"currencies":[{"id":"audit","displayName":"Audit","symbol":"A","scale":2,"defaultCurrency":true,"payable":true,"exchangeRate":"1"}]}'
}
foreach ($name in $files.Keys) {
    $target = Join-Path $fixture $name
    if (!(Test-Path -LiteralPath $target)) { Set-Content -LiteralPath $target -Value $files[$name] }
}
Write-Output "Prepared isolated fixture at $fixture. Existing world and config are preserved."
