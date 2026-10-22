param(
    [Parameter(Mandatory)][string]$JavaHome,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$LogPrefix='delivery-storage-native-2026-10-22'
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME=$JavaHome
$runId=[Guid]::NewGuid().ToString('N')
$relativeFixture="artifacts/delivery-storage-$runId-server"
$fixture=[IO.Path]::GetFullPath((Join-Path $taskRoot $relativeFixture))
$artifactRoot=[IO.Path]::GetFullPath((Join-Path $taskRoot 'artifacts'))+[IO.Path]::DirectorySeparatorChar
if(!$fixture.StartsWith($artifactRoot,[StringComparison]::OrdinalIgnoreCase)-or(Test-Path -LiteralPath $fixture)){throw 'Storage fixture must be fresh and inside task artifacts'}
New-Item -ItemType Directory -Path $fixture|Out-Null
foreach($part in @('mods','config','server.properties','eula.txt','delivery-audit-fixture.txt')){Copy-Item -LiteralPath (Join-Path $taskRoot "artifacts/delivery-callback-normal-server/$part") -Destination $fixture -Recurse}
$runId|Set-Content -LiteralPath (Join-Path $fixture 'delivery-storage-fixture.txt') -Encoding ascii
$arguments=@('--project-cache-dir','../artifacts/delivery-storage-native-cache','-I','../tools/delivery-runtime-audit.gradle','-Pneo_version=21.1.248',
    '-PdeliveryAuditBuildDir=../artifacts/delivery-storage-native-build',"-PdeliveryAuditFixture=../$relativeFixture","-PdeliveryAuditRun=$runId")
$stages=[Collections.Generic.List[object]]::new()
try{
    foreach($phase in @('failure','recovered')){
        Push-Location (Join-Path $taskRoot 'neoforge')
        try{& ./gradlew.bat @arguments "-PdeliveryAuditStorage=$phase" runServer --console=plain *> "../artifacts/$LogPrefix-$phase.log"
            if($LASTEXITCODE -ne 0){throw "Native storage audit failed in $phase; retained $relativeFixture"}
        }finally{Pop-Location}
        if((Get-Content -LiteralPath (Join-Path $fixture 'delivery-storage-fixture.txt') -Raw).Trim() -ne $runId){throw 'Storage fixture identity changed'}
        $guard=[IO.File]::Open((Join-Path $fixture 'delivery-world/session.lock'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)
        $guard.Dispose()
        $raw=Get-Content -LiteralPath (Join-Path $taskRoot "artifacts/$LogPrefix-$phase.log") -Raw
        $marker=[regex]::Match($raw,"\[DELIVERYAUDIT\] COMPLETE (\d+) storage checks phase=$phase")
        if(!$marker.Success){throw 'Missing native storage completion marker'}
        $stages.Add(@{phase=$phase;checks=[int]$marker.Groups[1].Value;log="artifacts/$LogPrefix-$phase.log"})
        Write-Output "PASS delivery storage $phase ($($marker.Groups[1].Value) checks)"
    }
}finally{
    @{run=$runId;fixture=$relativeFixture;createdAt=[DateTimeOffset]::Now.ToString('o');stages=$stages.ToArray()}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $taskRoot "artifacts/$LogPrefix-result.json") -Encoding utf8NoBOM
}
