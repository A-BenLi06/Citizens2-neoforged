param(
    [Parameter(Mandatory)][string]$JavaHome,
    [ValidatePattern('^21\.1\.\d+$')][string]$NeoVersion = '21.1.248',
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$LogPrefix = 'logging-crash-248'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = $JavaHome
$runId = [Guid]::NewGuid().ToString('N')
$buildDirectory = Join-Path $taskRoot 'artifacts/logging-crash-build'
$argumentFile = Join-Path $buildDirectory 'moddev/serverRunProgramArgs.txt'
$fixture = Join-Path $taskRoot 'artifacts/logging-audit-server'
$gradleArguments = @('--project-cache-dir','../artifacts/logging-crash-gradle-cache','-I','../tools/logging-runtime-audit.gradle',
    "-Pneo_version=$NeoVersion",'-PloggingAuditBuildDir=../artifacts/logging-crash-build',"-PloggingAuditRun=$runId")
$commitArguments = $gradleArguments + @('-PloggingAuditPhase=crash','runServer','--console=plain')
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
        if ($process.HasExited) { throw 'Logging crash audit exited before the kill marker' }
        if ((Test-Path -LiteralPath $log) -and (Get-Item -LiteralPath $log).LastWriteTimeUtc -ge $started) {
            [string]$raw = Get-Content -LiteralPath $log -Raw
            if ($null -eq $raw) { $raw = '' }
            $marker = [regex]::Match($raw,"\[LOGGINGAUDIT\] KILL_READY pid=(\d+) actor=([0-9a-f-]{36}) operation=([0-9a-f-]{36}) run=$runId checks=(\d+)")
            if ($marker.Success) {
                $fixtureProcessId = [int]$marker.Groups[1].Value
                $fixtureProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$fixtureProcessId"
                if ($null -eq $fixtureProcess -or $fixtureProcess.Name -ne 'java.exe' -or
                    $fixtureProcess.CommandLine.Replace('\\','\') -notlike "*$argumentFile*" -or
                    $fixtureProcess.CreationDate.ToUniversalTime() -lt $started) { throw 'Kill marker does not identify this fresh isolated logging JVM' }
                $statePath = Join-Path $fixture 'logging-restart-state.json'
                $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
                if ($state.run -ne $runId -or $state.actor -ne $marker.Groups[2].Value -or $state.operation -ne $marker.Groups[3].Value) {
                    throw 'Logging crash marker does not match the saved fixture state'
                }
                Stop-Process -Id $fixtureProcessId -Force
                Copy-Item -LiteralPath $statePath -Destination (Join-Path $taskRoot "artifacts/$LogPrefix-state.json")
                [ordered]@{run=$runId;neoForge=$NeoVersion;pid=$fixtureProcessId;actor=$state.actor;operation=$state.operation;
                    checks=[int]$marker.Groups[4].Value;processCreatedAt=$fixtureProcess.CreationDate.ToString('o');
                    killedAt=[DateTimeOffset]::Now.ToString('o');verifiedArgumentFile=$argumentFile;forcedTermination=$true} |
                    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRoot "artifacts/$LogPrefix-termination.json") -Encoding utf8
                Write-Output "Killed isolated logging audit JVM $fixtureProcessId after native history and settlement checks."
                $killed = $true
                break
            }
        }
        Start-Sleep -Milliseconds 250
    }
    if (!$killed) { throw 'Logging audit did not reach the durable marker' }
    if (!$process.WaitForExit(30000)) { throw 'Gradle did not observe the killed logging fixture' }
    if ($process.ExitCode -eq 0) { throw 'Logging crash audit unexpectedly ran normal shutdown' }
} finally {
    if ($process.HasExited) {
        [IO.File]::WriteAllText((Join-Path $taskRoot "artifacts/$LogPrefix-killed.log"),$stdout.GetAwaiter().GetResult())
        [IO.File]::WriteAllText((Join-Path $taskRoot "artifacts/$LogPrefix-killed-stderr.log"),$stderr.GetAwaiter().GetResult())
    }
    $process.Dispose()
}
Push-Location (Join-Path $taskRoot 'neoforge')
try {
    & ./gradlew.bat @gradleArguments '-PloggingAuditPhase=recover' runServer --console=plain *> "../artifacts/$LogPrefix-recovered.log"
    if ($LASTEXITCODE -ne 0) { throw 'Logging crash recovery failed' }
} finally { Pop-Location }
Write-Output 'PASS actual logging process kill, independent native histories and original Economy receipt recovery.'
