param([Parameter(Mandatory)][string]$JavaHome, [ValidateSet('receipt','projection','applied')][string]$Stage = 'receipt')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = $JavaHome
$outPath = Join-Path $taskRoot "artifacts/commerce-crash-$Stage-killed.log"
$errPath = Join-Path $taskRoot "artifacts/commerce-crash-$Stage-killed-stderr.log"
$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = (Get-Process -Id $PID).Path
$info.WorkingDirectory = Join-Path $taskRoot 'neoforge'
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
foreach ($argument in @('-NoProfile','-Command',"./gradlew.bat -I ../tools/xmod-runtime-audit.gradle '-Pneo_version=21.1.248' '-PcommerceAuditPhase=commit:$Stage' runServer --console=plain")) { $info.ArgumentList.Add($argument) }
$process = [Diagnostics.Process]::Start($info)
$stdout = $process.StandardOutput.ReadToEndAsync()
$stderr = $process.StandardError.ReadToEndAsync()
$started = [DateTime]::UtcNow
$log = Join-Path $taskRoot 'artifacts/xmod-audit-server/logs/latest.log'
try {
    $deadline = $started.AddMinutes(3)
    $killed = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw 'Crash audit exited before the kill marker' }
        if ((Test-Path -LiteralPath $log) -and (Get-Item -LiteralPath $log).LastWriteTimeUtc -ge $started) {
            [string]$raw = Get-Content -LiteralPath $log -Raw
            if ($null -eq $raw) { $raw = '' }
            $marker = [regex]::Match($raw,'\[COMMERCETASKAUDIT\] KILL_READY pid=(\d+) entry=([0-9a-f]{64})')
            if ($marker.Success) {
                $fixtureProcessId = [int]$marker.Groups[1].Value
                $fixtureProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$fixtureProcessId"
                if ($fixtureProcess.Name -ne 'java.exe' -or $fixtureProcess.CommandLine -notmatch 'serverRunProgramArgs.txt') { throw 'Kill marker does not identify the opt-in audit JVM' }
                Stop-Process -Id $fixtureProcessId -Force
                Write-Output "Killed isolated commerce audit JVM $fixtureProcessId after actual Economy commit."
                $killed = $true
                break
            }
        }
        Start-Sleep -Milliseconds 250
    }
    if (!$killed) { throw 'Crash audit did not reach the durable commit marker' }
    if (!$process.WaitForExit(30000)) { throw 'Gradle did not observe the killed fixture' }
    [IO.File]::WriteAllText($outPath,$stdout.GetAwaiter().GetResult())
    [IO.File]::WriteAllText($errPath,$stderr.GetAwaiter().GetResult())
    if ($process.ExitCode -eq 0) { throw 'Crash audit unexpectedly ran normal shutdown' }
} finally { $process.Dispose() }
Push-Location (Join-Path $taskRoot 'neoforge')
try {
    & ./gradlew.bat -I ../tools/xmod-runtime-audit.gradle '-Pneo_version=21.1.248' '-PcommerceAuditPhase=recover' runServer --console=plain *> "../artifacts/commerce-crash-$Stage-recovered.log"
    if ($LASTEXITCODE -ne 0) { throw 'Commerce crash recovery failed' }
} finally { Pop-Location }
Write-Output 'PASS actual process kill, durable Economy receipt replay, absolute native FTB projection and delivery recovery.'
