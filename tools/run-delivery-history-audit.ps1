param(
    [Parameter(Mandatory)][string]$JavaHome,
    [ValidateSet('absent','resolved')][string]$Shape='absent',
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$LogPrefix='delivery-history-2026-10-22'
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME=$JavaHome
$runId=[Guid]::NewGuid().ToString('N')
$relativeFixture="artifacts/delivery-history-$runId-server"
$fixture=[IO.Path]::GetFullPath((Join-Path $taskRoot $relativeFixture))
$artifactRoot=[IO.Path]::GetFullPath((Join-Path $taskRoot 'artifacts'))+[IO.Path]::DirectorySeparatorChar
if(!$fixture.StartsWith($artifactRoot,[StringComparison]::OrdinalIgnoreCase)-or(Test-Path -LiteralPath $fixture)){throw 'History fixture must be fresh and inside task artifacts'}
New-Item -ItemType Directory -Path $fixture|Out-Null
foreach($part in @('mods','config','server.properties','eula.txt','delivery-audit-fixture.txt')){Copy-Item -LiteralPath (Join-Path $taskRoot "artifacts/delivery-callback-normal-server/$part") -Destination $fixture -Recurse}
$runId|Set-Content -LiteralPath (Join-Path $fixture 'delivery-history-fixture.txt') -Encoding ascii
$arguments=@('--project-cache-dir','../artifacts/delivery-history-cache','-I','../tools/delivery-runtime-audit.gradle','-Pneo_version=21.1.248',
    '-PdeliveryAuditBuildDir=../artifacts/delivery-history-build',"-PdeliveryAuditFixture=../$relativeFixture","-PdeliveryAuditRun=$runId","-PdeliveryAuditShape=$Shape")
$stages=[Collections.Generic.List[object]]::new()
function Assert-Stopped {
    if((Get-Content -LiteralPath (Join-Path $fixture 'delivery-history-fixture.txt') -Raw).Trim() -ne $runId){throw 'History fixture identity changed'}
    $guard=[IO.File]::Open((Join-Path $fixture 'delivery-world/session.lock'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)
    $guard.Dispose()
}
function Assert-FixturePath([string]$path){
    $resolved=[IO.Path]::GetFullPath($path)
    if(!$resolved.StartsWith($fixture+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'History restore path escaped its fixture'}
    return $resolved
}
function Invoke-Phase([string]$phase){
    Push-Location (Join-Path $taskRoot 'neoforge')
    try{& ./gradlew.bat @arguments "-PdeliveryAuditHistory=$phase" runServer --console=plain *> "../artifacts/$LogPrefix-$phase.log"
        if($LASTEXITCODE -ne 0){throw "History audit failed in $phase; retained $relativeFixture"}
    }finally{Pop-Location}
    Assert-Stopped
    $raw=Get-Content -LiteralPath (Join-Path $taskRoot "artifacts/$LogPrefix-$phase.log") -Raw
    $marker=[regex]::Match($raw,"\[DELIVERYAUDIT\] COMPLETE (\d+) history checks phase=$phase shape=$Shape")
    if(!$marker.Success){throw 'Missing native history completion marker'}
    $stages.Add(@{phase=$phase;checks=[int]$marker.Groups[1].Value;log="artifacts/$LogPrefix-$phase.log"})
    Write-Output "PASS delivery history $Shape $phase ($($marker.Groups[1].Value) checks)"
}
try{
    Invoke-Phase 'seed'
    $world=Assert-FixturePath (Join-Path $fixture 'delivery-world')
    $seedWorld=Assert-FixturePath (Join-Path $fixture 'seed-world')
    Copy-Item -LiteralPath $world -Destination $seedWorld -Recurse
    Assert-Stopped
    $journal=Assert-FixturePath (Join-Path $world 'citizens/quest-deliveries')
    $prefix=Assert-FixturePath (Join-Path $fixture 'history-prefix')
    foreach($file in Get-ChildItem -LiteralPath $journal -Filter '*.json' -File){
        $path=Assert-FixturePath $file.FullName
        if(![IO.Path]::GetFullPath($path).StartsWith($journal+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected journal restore target'}
        Remove-Item -LiteralPath $path
    }
    foreach($file in Get-ChildItem -LiteralPath $prefix -Filter '*.json' -File){Copy-Item -LiteralPath $file.FullName -Destination $journal}
    Invoke-Phase 'missing'
    Assert-Stopped
    $missingWorld=Assert-FixturePath (Join-Path $fixture 'missing-world')
    if(Test-Path -LiteralPath $missingWorld){throw 'History observation snapshot already exists'}
    # Both resolved move targets were checked above and belong to this fresh fixture.
    Move-Item -LiteralPath $world -Destination $missingWorld
    Copy-Item -LiteralPath $seedWorld -Destination $world -Recurse
    Invoke-Phase 'consistent'
}finally{
    @{run=$runId;fixture=$relativeFixture;shape=$Shape;createdAt=[DateTimeOffset]::Now.ToString('o');stages=$stages.ToArray()}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $taskRoot "artifacts/$LogPrefix-result.json") -Encoding utf8NoBOM
}
