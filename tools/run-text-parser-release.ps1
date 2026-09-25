param(
    [Parameter(Mandatory)][string]$LibraryDirectory,
    [Parameter(Mandatory)][string]$JavaHome
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'prepare-text-parser-release.ps1') -LibraryDirectory $LibraryDirectory
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/text-parser-release-server'
$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = Join-Path $JavaHome 'bin/java.exe'
$info.WorkingDirectory = $fixture
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardInput = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
# The fixture asserts English console messages; its locale is independent of the host and production server.
foreach ($argument in @('-Xmx1G', '-Duser.language=en', '-Duser.country=US', '@launch-args.txt', '--gameDir', $fixture, 'nogui')) { $info.ArgumentList.Add($argument) }
$started = [DateTime]::UtcNow
$process = [Diagnostics.Process]::Start($info)
$stdout = $process.StandardOutput.ReadToEndAsync()
$stderr = $process.StandardError.ReadToEndAsync()
try {
    $log = Join-Path $fixture 'logs/latest.log'
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    while ($true) {
        if ($process.HasExited) { throw "Release server exited during startup: $($process.ExitCode)" }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Release server startup timed out' }
        if ((Test-Path -LiteralPath $log) -and (Get-Item -LiteralPath $log).LastWriteTimeUtc -ge $started) {
            # Log rotation can expose a newly created empty file before the first server message.
            [string]$raw = Get-Content -LiteralPath $log -Raw
            if ($raw.Contains('Citizens enabled.') -and $raw.Contains('For help, type "help"')) { break }
        }
        Start-Sleep -Milliseconds 250
    }
    Write-Output "Release server ready (PID $($process.Id)); exercising the packaged parser"
    $process.StandardInput.WriteLine('npc create <gradient:red:blue>PackageParser</gradient> --type COW --at 0,-60,0')
    $process.StandardInput.Flush()
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ((Get-Content -LiteralPath $log -Raw) -notmatch 'Created .*PackageParser') {
        if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Packaged NPC creation did not succeed' }
        Start-Sleep -Milliseconds 250
    }
    $process.StandardInput.WriteLine('npc list')
    $process.StandardInput.WriteLine('stop')
    $process.StandardInput.Flush()
    if (!$process.WaitForExit(60000)) { throw 'Release server did not stop' }
    if ($process.ExitCode -ne 0) { throw "Release server exited $($process.ExitCode)" }
    $raw = Get-Content -LiteralPath $log -Raw
    if ($raw -match 'NoClassDefFoundError|ClassNotFoundException|ModLoadingException|Exception executing command|Created .*<gradient') {
        throw 'Packaged parser smoke check failed; inspect the isolated log'
    }
    if ($raw -notmatch '\d+ - PackageParser \(cow, spawned\)' -or !$raw.Contains('Citizens disabled.')) {
        throw 'Packaged NPC list or shutdown did not complete'
    }
    Write-Output 'PASS packaged Citizens and Interactions startup, gradient NPC creation, list and clean shutdown'
} finally {
    if (!$process.HasExited) {
        $process.StandardInput.WriteLine('stop')
        $process.StandardInput.Flush()
        if (!$process.WaitForExit(10000)) { $process.Kill(); $process.WaitForExit() }
    }
    [IO.File]::WriteAllText((Join-Path $fixture 'stdout.log'), $stdout.GetAwaiter().GetResult())
    [IO.File]::WriteAllText((Join-Path $fixture 'stderr.log'), $stderr.GetAwaiter().GetResult())
    $process.Dispose()
}
