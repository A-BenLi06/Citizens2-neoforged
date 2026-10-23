param(
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$ProviderJar,
    [Parameter(Mandatory)][string]$Slf4jJar,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$output = [IO.Path]::GetFullPath($OutputDirectory)
$artifactRoot = [IO.Path]::GetFullPath((Join-Path $taskRoot 'artifacts')) + [IO.Path]::DirectorySeparatorChar
if (!$output.StartsWith($artifactRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Storage test output must be inside task artifacts' }
if (Test-Path -LiteralPath $output) { throw 'Storage tests require a fresh output directory' }
$provider = (Get-Item -LiteralPath $ProviderJar).FullName
$slf4j = (Get-Item -LiteralPath $Slf4jJar).FullName
New-Item -ItemType Directory -Path $output, (Join-Path $output 'classes'), (Join-Path $output 'databases') | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($provider)
$sqlite = Join-Path $output 'sqlite.jar'
try {
    $metadataEntry = $archive.GetEntry('META-INF/jarjar/metadata.json')
    if (!$metadataEntry) { throw 'Provider Jar-in-Jar metadata missing' }
    $reader = [IO.StreamReader]::new($metadataEntry.Open())
    try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    $drivers = @($metadata.jars | Where-Object { $_.identifier.group -eq 'org.xerial' -and $_.identifier.artifact -eq 'sqlite-jdbc' })
    if ($drivers.Count -ne 1) { throw 'Provider must declare exactly one nested SQLite driver' }
    $driverEntry = $archive.GetEntry($drivers[0].path)
    if (!$driverEntry) { throw 'Declared provider SQLite driver missing' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($driverEntry, $sqlite, $false)
} finally { $archive.Dispose() }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'storage-tests') -Filter '*.java' -File -Recurse)
$classPath = $provider + ';' + $slf4j + ';' + $sqlite
& (Join-Path $JavaHome 'bin/javac.exe') -cp $classPath -d (Join-Path $output 'classes') @($sources.FullName)
if ($LASTEXITCODE -ne 0) { throw 'Storage test compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -cp ((Join-Path $output 'classes') + ';' + $classPath) StorageRecoveryTest (Join-Path $output 'databases') 2>&1 |
    Tee-Object -FilePath (Join-Path $output 'tests.log')
if ($LASTEXITCODE -ne 0) { throw 'Storage tests failed; retained output identifies the first assertion' }
$raw = Get-Content -LiteralPath (Join-Path $output 'tests.log') -Raw
$complete = [regex]::Match($raw,'\[GRIEFSTORAGE\] COMPLETE (\d+) checks')
if (!$complete.Success) { throw 'Storage test completion marker missing' }
[ordered]@{
    createdAt = [DateTimeOffset]::Now.ToString('o')
    timezone = 'UTC+08:00'
    checks = [int]$complete.Groups[1].Value
    actualProviderBytecode = $true
    minecraftBootstrapOwnerSubstitutedForStandaloneLogger = $true
    files = @(@($provider,$slf4j,$sqlite,(Join-Path $output 'tests.log'),$PSCommandPath) + @($sources.FullName) | ForEach-Object {
        $file = Get-Item -LiteralPath $_
        [ordered]@{ path=$file.FullName; bytes=$file.Length; sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'validation.json') -Encoding utf8NoBOM
