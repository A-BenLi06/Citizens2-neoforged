param(
    [Parameter(Mandatory)][string]$JavaHome,
    [ValidateSet('actor','shared','offline','newmember','dependency','sequential','reward','rewardshared')][string]$Scope = 'actor',
    [string]$EconomyJar,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$LogPrefix = 'financial-backup-2026-10-22'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = $JavaHome
$runId = [Guid]::NewGuid().ToString('N')
$relativeFixture = "artifacts/financial-backup-$runId-server"
$fixture = [IO.Path]::GetFullPath((Join-Path $taskRoot $relativeFixture))
$artifactRoot = [IO.Path]::GetFullPath((Join-Path $taskRoot 'artifacts')) + [IO.Path]::DirectorySeparatorChar
if (!$fixture.StartsWith($artifactRoot, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $fixture)) {
    throw 'Financial fixture must be a fresh directory inside task artifacts'
}
$mods = Join-Path $fixture 'mods'
New-Item -ItemType Directory $mods | Out-Null
foreach ($name in @('ftb-library-neoforge-2101.1.37.jar','ftb-quests-neoforge-2101.1.36.jar','ftb-teams-neoforge-2101.1.11.jar')) {
    Copy-Item -LiteralPath (Join-Path $taskRoot "artifacts/selected-integrations-2026-10-12/$name") -Destination $mods
}
Copy-Item -LiteralPath (Join-Path $taskRoot 'artifacts/ecosystem-2026-10-13/server-smoke/mods/architectury-13.0.11-neoforge.jar') -Destination $mods
if (!$EconomyJar) { $EconomyJar = Join-Path $taskRoot '../yuuniverse-economy/build/libs/yuuniverse-economy-0.5.7-SNAPSHOT.jar' }
Copy-Item -LiteralPath $EconomyJar -Destination (Join-Path $mods 'economy.jar')
@('server-ip=127.0.0.1','server-port=25646','online-mode=false','level-name=financial-world',
  'level-type=minecraft:flat','generate-structures=false','spawn-protection=0','view-distance=2','simulation-distance=2',
  'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}') |
    Set-Content -LiteralPath (Join-Path $fixture 'server.properties') -Encoding utf8NoBOM
'eula=true' | Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding ascii
$runId | Set-Content -LiteralPath (Join-Path $fixture 'financial-audit-fixture.txt') -Encoding ascii
$gradleArguments = @('--project-cache-dir','../artifacts/financial-backup-cache','-I','../tools/financial-backup-runtime-audit.gradle',
    '-Pneo_version=21.1.248',"-PfinancialAuditFixture=../$relativeFixture","-PfinancialAuditRun=$runId","-PfinancialAuditScope=$Scope")
$stages = [Collections.Generic.List[object]]::new()
function Assert-StoppedFixture {
    if ((Get-Content -LiteralPath (Join-Path $fixture 'financial-audit-fixture.txt') -Raw).Trim() -ne $runId) { throw 'Financial fixture identity changed' }
    $lockPath = Join-Path $fixture 'financial-world/session.lock'
    $guard = [IO.File]::Open($lockPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
    $guard.Dispose()
}
function Invoke-Phase([string]$phase) {
    Push-Location (Join-Path $taskRoot 'neoforge')
    try {
        & ./gradlew.bat @gradleArguments "-PfinancialAuditPhase=$phase" runServer --console=plain *> "../artifacts/$LogPrefix-$phase.log"
        if ($LASTEXITCODE -ne 0) { throw "Financial backup audit failed in $phase; retained $relativeFixture" }
    } finally { Pop-Location }
    Assert-StoppedFixture
    $raw = Get-Content -LiteralPath (Join-Path $taskRoot "artifacts/$LogPrefix-$phase.log") -Raw
    $marker = [regex]::Match($raw,"\[FINANCIALAUDIT\] COMPLETE (\d+) $phase checks run=$runId")
    if (!$marker.Success) { throw 'Missing fresh financial audit completion marker' }
    $stages.Add([ordered]@{phase=$phase;checks=[int]$marker.Groups[1].Value;log="artifacts/$LogPrefix-$phase.log"})
    Write-Output "PASS financial backup $phase ($($marker.Groups[1].Value) checks)"
}
try {
    Invoke-Phase 'seed'
    $state = Get-Content -LiteralPath (Join-Path $fixture 'financial-state.json') -Raw | ConvertFrom-Json
    if ($state.run -ne $runId) { throw 'Seed state belongs to a different run' }
    $playerPath = Join-Path $fixture "financial-world/playerdata/$($state.actor).dat"
    $playerSnapshot = Join-Path $fixture 'financial-player.dat'
    Copy-Item -LiteralPath $playerPath -Destination $playerSnapshot
    if ($Scope -eq 'newmember') {
        $newActorId = [Guid]::Parse($state.newActor).ToString()
        $newPlayerPath = Join-Path $fixture "financial-world/playerdata/$newActorId.dat"
        $newPlayerSnapshot = Join-Path $fixture 'financial-new-player.dat'
        Copy-Item -LiteralPath $newPlayerPath -Destination $newPlayerSnapshot
    }
    $database = Join-Path $fixture 'financial-world/yuuniverse_economy/economy.db'
    foreach ($phase in @('regressed','caughtup','higher','consistent')) {
        Assert-StoppedFixture
        foreach ($suffix in @('-wal','-shm')) {
            if (Test-Path -LiteralPath "$database$suffix") { throw 'Stopped financial fixture still has SQLite sidecar state; refusing partial replacement' }
        }
        $backupName = if ($phase -eq 'consistent') { 'financial-after.db' } else { 'financial-before.db' }
        Copy-Item -LiteralPath (Join-Path $fixture $backupName) -Destination $database -Force
        Copy-Item -LiteralPath $playerSnapshot -Destination $playerPath -Force
        if ($Scope -eq 'newmember') { Copy-Item -LiteralPath $newPlayerSnapshot -Destination $newPlayerPath -Force }
        if ($Scope -in @('offline','newmember','dependency','sequential')) {
            $teamId = [Guid]::Parse($state.team).ToString()
            Copy-Item -LiteralPath (Join-Path $fixture 'financial-progress-before.snbt') -Destination (Join-Path $fixture "financial-world/ftbquests/$teamId.snbt") -Force
        }
        Invoke-Phase $phase
    }
} finally {
    [ordered]@{run=$runId;fixture=$relativeFixture;scope=$Scope;neoForge='21.1.248';createdAt=[DateTimeOffset]::Now.ToString('o');stages=$stages.ToArray()} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskRoot "artifacts/$LogPrefix-result.json") -Encoding utf8NoBOM
}
