param(
    [Parameter(Mandatory)][string]$JavaHome,
    [ValidateSet('before','progress','inventory','confirmed','player_rollback','task_rollback')][string]$Boundary = 'inventory',
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$LogPrefix = 'delivery-crash-248',
    [string]$FixtureDirectory = 'artifacts/delivery-audit-server',
    [string]$BuildDirectory = 'artifacts/delivery-native-build',
    [string]$ProjectCache = 'artifacts/delivery-native-gradle-cache'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = $JavaHome
$runId = [Guid]::NewGuid().ToString('N')
$artifactRoot = [IO.Path]::GetFullPath((Join-Path $taskRoot 'artifacts')) + [IO.Path]::DirectorySeparatorChar
function Resolve-AuditPath([string]$path) {
    $resolved = [IO.Path]::GetFullPath((Join-Path $taskRoot $path))
    if (!$resolved.StartsWith($artifactRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Delivery audit paths must be inside task artifacts' }
    return $resolved
}
$fixture = Resolve-AuditPath $FixtureDirectory
$buildOutput = Resolve-AuditPath $BuildDirectory
$cache = Resolve-AuditPath $ProjectCache
if ((Get-Content -LiteralPath (Join-Path $fixture 'delivery-audit-fixture.txt') -Raw).Trim() -ne 'citizens-delivery-audit') { throw 'Missing delivery fixture identity' }
$argumentFile = Join-Path $buildOutput 'moddev/serverRunProgramArgs.txt'
$gradleArguments = @('--project-cache-dir',$cache,'-I','../tools/delivery-runtime-audit.gradle',
    "-PdeliveryAuditFixture=$fixture","-PdeliveryAuditBuildDir=$buildOutput",'-Pneo_version=21.1.248',"-PdeliveryAuditRun=$runId","-PdeliveryAuditBoundary=$Boundary")
$commitArguments = $gradleArguments + @('-PdeliveryAuditPhase=crash','runServer','--console=plain')
$escapedArguments = $commitArguments | ForEach-Object { "'" + $_.Replace("'","''") + "'" }
$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = (Get-Process -Id $PID).Path
$info.WorkingDirectory = Join-Path $taskRoot 'neoforge'
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
foreach ($argument in @('-NoProfile','-Command',('& ./gradlew.bat ' + ($escapedArguments -join ' ')))) { $info.ArgumentList.Add($argument) }
$process = [Diagnostics.Process]::Start($info)
$stdout = $process.StandardOutput.ReadToEndAsync()
$stderr = $process.StandardError.ReadToEndAsync()
$started = [DateTime]::UtcNow
$log = Join-Path $fixture 'logs/latest.log'
try {
    $deadline = $started.AddMinutes(4)
    $killed = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw 'Delivery crash audit exited before the kill marker' }
        if ((Test-Path -LiteralPath $log) -and (Get-Item -LiteralPath $log).LastWriteTimeUtc -ge $started) {
            [string]$raw = Get-Content -LiteralPath $log -Raw
            if ($null -eq $raw) { $raw = '' }
            $marker = [regex]::Match($raw,"\[DELIVERYAUDIT\] KILL_READY pid=(\d+) actor=([0-9a-f-]{36}) entry=([0-9a-f-]{36}) run=$runId boundary=$Boundary")
            if ($marker.Success) {
                $fixtureProcessId = [int]$marker.Groups[1].Value
                $fixtureProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$fixtureProcessId"
                if ($null -eq $fixtureProcess -or $fixtureProcess.Name -ne 'java.exe' -or
                    $fixtureProcess.CommandLine.Replace('\\','\') -notlike "*$argumentFile*" -or
                    $fixtureProcess.CreationDate.ToUniversalTime() -lt $started) { throw 'Marker does not identify this fresh isolated delivery JVM' }
                $statePath = Join-Path $fixture 'delivery-crash-state.json'
                $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
                if ($state.run -ne $runId -or $state.boundary -ne $Boundary -or $state.actor -ne $marker.Groups[2].Value -or $state.entry -ne $marker.Groups[3].Value) {
                    throw 'Delivery marker does not match the saved fixture identity'
                }
                Stop-Process -Id $fixtureProcessId -Force
                Copy-Item -LiteralPath $statePath -Destination (Join-Path $taskRoot "artifacts/$LogPrefix-state.json")
                [ordered]@{run=$runId;boundary=$Boundary;pid=$fixtureProcessId;actor=$state.actor;entry=$state.entry;
                    processCreatedAt=$fixtureProcess.CreationDate.ToString('o');killedAt=[DateTimeOffset]::Now.ToString('o');
                    verifiedArgumentFile=$argumentFile;fixture=$fixture;buildOutput=$buildOutput;forcedTermination=$true} |
                    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRoot "artifacts/$LogPrefix-termination.json") -Encoding utf8NoBOM
                Write-Output "Killed isolated delivery JVM $fixtureProcessId at $Boundary."
                $killed = $true; break
            }
        }
        Start-Sleep -Milliseconds 250
    }
    if (!$killed) { throw 'Delivery audit did not reach the durable marker' }
    if (!$process.WaitForExit(30000)) { throw 'Gradle did not observe the killed delivery fixture' }
    if ($process.ExitCode -eq 0) { throw 'Delivery crash audit unexpectedly shut down normally' }
} finally {
    if ($process.HasExited) {
        [IO.File]::WriteAllText((Join-Path $taskRoot "artifacts/$LogPrefix-killed.log"), $stdout.GetAwaiter().GetResult())
        [IO.File]::WriteAllText((Join-Path $taskRoot "artifacts/$LogPrefix-killed-stderr.log"), $stderr.GetAwaiter().GetResult())
    }
    $process.Dispose()
}
Push-Location (Join-Path $taskRoot 'neoforge')
try {
    & ./gradlew.bat @gradleArguments '-PdeliveryAuditPhase=recover' runServer --console=plain *> "../artifacts/$LogPrefix-recovered.log"
    if ($LASTEXITCODE -ne 0) { throw 'Delivery crash recovery failed' }
} finally { Pop-Location }
Write-Output "PASS actual delivery process kill and recovery at $Boundary."
