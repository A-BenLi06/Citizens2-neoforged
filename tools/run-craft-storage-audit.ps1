param(
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][string]$GsonJar,
    [Parameter(Mandatory)][ValidateSet('scan','indexed')][string]$Mode,
    [Parameter(Mandatory)][string]$Fixture,
    [Parameter(Mandatory)][string]$BuildDirectory
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$artifactRoot=[IO.Path]::GetFullPath((Join-Path $taskRoot 'artifacts'))+[IO.Path]::DirectorySeparatorChar
function Resolve-AuditPath([string]$path) {
    $resolved=[IO.Path]::GetFullPath((Join-Path $taskRoot $path))
    if (!$resolved.StartsWith($artifactRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Craft storage output must be inside artifacts' }
    return $resolved
}
$output=Resolve-AuditPath $BuildDirectory
$fixturePath=Resolve-AuditPath $Fixture
if (Test-Path -LiteralPath $output) { throw 'Use a fresh build directory to preserve sources and classes' }
if (($Mode -eq 'scan') -eq (Test-Path -LiteralPath $fixturePath)) { throw 'Scan needs a fresh fixture; indexed needs the retained scan fixture' }
New-Item -ItemType Directory -Path $output|Out-Null
$sources=@('neoforge/src/main/java/net/citizensnpcs/util/QuestEvidenceJournal.java',
    'neoforge/src/main/java/net/citizensnpcs/util/JournalJson.java',
    'tools/craft-storage-audit/net/citizensnpcs/audit/CraftStorageAudit.java')
$copied=foreach($source in $sources) {
    $target=Join-Path $output ([IO.Path]::GetFileName($source))
    Copy-Item -LiteralPath (Join-Path $taskRoot $source) -Destination $target
    $target
}
& (Join-Path $JavaHome 'bin/javac.exe') -cp $GsonJar -d (Join-Path $output 'classes') @copied
if ($LASTEXITCODE -ne 0) { throw 'Craft storage compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -Xmx1G -cp "$(Join-Path $output 'classes');$GsonJar" net.citizensnpcs.audit.CraftStorageAudit $fixturePath $Mode
if ($LASTEXITCODE -ne 0) { throw 'Craft storage workload failed' }
