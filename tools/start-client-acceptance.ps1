param([string]$JavaHome = $env:JAVA_HOME, [ValidateRange(1,32)][int]$MaxHeapGB = 4)
$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/ordinary-client-acceptance-server'
$manifest = Get-Content -LiteralPath (Join-Path $fixture 'acceptance-manifest.json') -Raw | ConvertFrom-Json
if ($manifest.marker -ne 'citizens-ordinary-client-acceptance') { throw 'Missing acceptance fixture identity' }
$properties = Get-Content -LiteralPath (Join-Path $fixture 'server.properties')
foreach ($required in @('server-ip=127.0.0.1', "server-port=$($manifest.port)", 'level-name=acceptance-world', 'enable-rcon=false')) {
    if (@($properties | Where-Object { $_ -match ('^' + [regex]::Escape(($required -split '=')[0]) + '=') }).Count -ne 1 -or
        $required -notin $properties) { throw "Unexpected acceptance property: $required" }
}
$mods = @(Get-ChildItem -LiteralPath (Join-Path $fixture 'mods') -File -Filter '*.jar')
if ($mods.Count -ne $manifest.files.Count) { throw 'Acceptance mod set changed; preserve and review the manifest before running' }
foreach ($file in $manifest.files) {
    if ((Get-FileHash -LiteralPath (Join-Path $fixture "mods/$($file.name)") -Algorithm SHA256).Hash -ne $file.sha256) {
        throw "Acceptance artifact changed: $($file.name)"
    }
}
if (Get-NetTCPConnection -LocalPort $manifest.port -State Listen -ErrorAction SilentlyContinue) { throw 'Acceptance port is already occupied' }
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Pass -JavaHome with a Java 21 installation' }
Push-Location $fixture
try {
    & (Join-Path $JavaHome 'bin/java.exe') "-Xmx${MaxHeapGB}G" '@launch-args.txt' '--nogui'
    if ($LASTEXITCODE -ne 0) { throw "Acceptance server exited with code $LASTEXITCODE" }
} finally { Pop-Location }
