param(
    [Parameter(Mandatory)][string]$JavaHome,
    [ValidateSet('prepared','confirmed','applied')][string]$Stage = 'prepared',
    [ValidateSet('21.1.233','21.1.248')][string]$NeoVersion = '21.1.248',
    [ValidateSet('depot','belt','deployer','spout')][string]$AssemblyMachine,
    [ValidateSet('basin','fan-world','fan-depot','fan-belt')][string]$ProcessingMachine,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$LogPrefix,
    [string]$Fixture,
    [string]$BuildDirectory,
    [string]$ProjectCacheDirectory
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
if ($AssemblyMachine -and $ProcessingMachine) { throw 'Select one native machine family' }
if (!$Fixture) { $Fixture = Join-Path $taskRoot 'artifacts/create-audit-server' }
if (!$BuildDirectory) { $BuildDirectory = Join-Path $taskRoot 'artifacts/machine-crash-build' }
if (!$ProjectCacheDirectory) { $ProjectCacheDirectory = Join-Path $taskRoot 'artifacts/machine-crash-gradle-cache' }
$artifactRoot = [IO.Path]::GetFullPath((Join-Path $taskRoot 'artifacts')) + [IO.Path]::DirectorySeparatorChar
foreach ($candidate in @($Fixture,$BuildDirectory,$ProjectCacheDirectory)) {
    if (![IO.Path]::GetFullPath($candidate).StartsWith($artifactRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Machine audit paths must be inside task artifacts' }
}
$Fixture = [IO.Path]::GetFullPath($Fixture)
$buildDirectory = [IO.Path]::GetFullPath($BuildDirectory)
$ProjectCacheDirectory = [IO.Path]::GetFullPath($ProjectCacheDirectory)
if ($buildDirectory -eq $ProjectCacheDirectory -or $ProjectCacheDirectory.StartsWith($buildDirectory + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Project cache must remain outside the cleanable build directory'
}
if ((Get-Content -LiteralPath (Join-Path $Fixture 'create-audit-fixture.txt') -Raw).Trim() -ne 'citizens-create-audit') { throw 'Unexpected fixture marker' }
$auditType = if ($AssemblyMachine) { $AssemblyMachine } else { $ProcessingMachine }
$env:JAVA_HOME = $JavaHome
$runId = [Guid]::NewGuid().ToString('N')
if (!$LogPrefix) { $LogPrefix = if ($AssemblyMachine) { "assembly-crash-$AssemblyMachine-$Stage-$NeoVersion" } elseif ($ProcessingMachine) { "processing-crash-$ProcessingMachine-$Stage-$NeoVersion" } else { "machine-crash-$Stage" } }
$stateName = if ($AssemblyMachine) { 'assembly-crash-state.json' } elseif ($ProcessingMachine -like 'fan-*') { 'fan-crash-state.json' } elseif ($ProcessingMachine) { 'basin-crash-state.json' } else { 'machine-crash-state.json' }
$gradleArguments = @('--project-cache-dir',$ProjectCacheDirectory,'-I','../tools/create-runtime-audit.gradle',"-Pneo_version=$NeoVersion","-PmachineAuditBuildDir=$buildDirectory","-PmachineAuditFixture=$Fixture","-PmachineAuditRun=$runId")
if ($auditType) { $gradleArguments += "-PmachineAuditType=$auditType" }
$commitArguments = $gradleArguments + @("-PmachineAuditPhase=commit:$Stage",'runServer','--console=plain')
$escapedArguments = $commitArguments | ForEach-Object { "'" + $_.Replace("'","''") + "'" }
$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = (Get-Process -Id $PID).Path
$info.WorkingDirectory = Join-Path $taskRoot 'neoforge'
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
foreach ($argument in @('-NoProfile','-Command',("& ./gradlew.bat " + ($escapedArguments -join ' ')))) { $info.ArgumentList.Add($argument) }
$process = [Diagnostics.Process]::Start($info)
$stdout = $process.StandardOutput.ReadToEndAsync()
$stderr = $process.StandardError.ReadToEndAsync()
$started = [DateTime]::UtcNow
$log = Join-Path $Fixture 'logs/latest.log'
try {
    $deadline = $started.AddMinutes(3)
    $killed = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw 'Machine crash audit exited before the kill marker' }
        if ((Test-Path -LiteralPath $log) -and (Get-Item -LiteralPath $log).LastWriteTimeUtc -ge $started) {
            [string]$raw = Get-Content -LiteralPath $log -Raw
            if ($null -eq $raw) { $raw = '' }
            $marker = [regex]::Match($raw,"\[MACHINECRASHAUDIT\] KILL_READY pid=(\d+) entry=([0-9a-f]{64}) run=$runId")
            if ($marker.Success) {
                $fixtureProcessId = [int]$marker.Groups[1].Value
                $fixtureProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$fixtureProcessId"
                $argumentFile = Join-Path $buildDirectory 'moddev/serverRunProgramArgs.txt'
                $commandLine = $fixtureProcess.CommandLine.Replace('\\','\')
                if ($fixtureProcess.Name -ne 'java.exe' -or $commandLine -notlike "*$argumentFile*" -or
                    $fixtureProcess.CreationDate.ToUniversalTime() -lt $started) { throw 'Kill marker does not identify this fresh isolated audit JVM' }
                Stop-Process -Id $fixtureProcessId -Force
                Copy-Item -LiteralPath (Join-Path $Fixture $stateName) -Destination (Join-Path $taskRoot "artifacts/$LogPrefix-state.json")
                [ordered]@{run=$runId;stage=$Stage;neoForge=$NeoVersion;machine=$auditType;pid=$fixtureProcessId;fixture=$Fixture;projectCache=$ProjectCacheDirectory;
                    processCreatedAt=$fixtureProcess.CreationDate.ToString('o');killedAt=[DateTimeOffset]::Now.ToString('o');
                    verifiedArgumentFile=$argumentFile;entry=$marker.Groups[2].Value;forcedTermination=$true} |
                    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRoot "artifacts/$LogPrefix-termination.json") -Encoding utf8
                Write-Output "Killed isolated machine audit JVM $fixtureProcessId at $Stage."
                $killed = $true
                break
            }
        }
        Start-Sleep -Milliseconds 250
    }
    if (!$killed) { throw 'Machine audit did not reach the durable marker' }
    if (!$process.WaitForExit(30000)) { throw 'Gradle did not observe the killed fixture' }
    if ($process.ExitCode -eq 0) { throw 'Crash audit unexpectedly ran normal shutdown' }
} finally {
    if ($process.HasExited) {
        [IO.File]::WriteAllText((Join-Path $taskRoot "artifacts/$LogPrefix-killed.log"),$stdout.GetAwaiter().GetResult())
        [IO.File]::WriteAllText((Join-Path $taskRoot "artifacts/$LogPrefix-killed-stderr.log"),$stderr.GetAwaiter().GetResult())
    }
    $process.Dispose()
}
Push-Location (Join-Path $taskRoot 'neoforge')
try {
    & ./gradlew.bat @gradleArguments '-PmachineAuditPhase=recover' runServer --console=plain *> "../artifacts/$LogPrefix-recovered.log"
    if ($LASTEXITCODE -ne 0) { throw 'Machine crash recovery failed' }
} finally { Pop-Location }
Write-Output 'PASS actual native machine process kill and durable evidence recovery.'
