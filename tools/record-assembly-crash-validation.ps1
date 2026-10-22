param(
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$EvidenceDate = '2026-10-20',
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$RunRevision = 'v2',
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$RegressionDate = $EvidenceDate
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$previous = Get-Content -LiteralPath (Join-Path $taskRoot "docs/create-assembly-recovery-validation-$EvidenceDate.json") -Raw | ConvertFrom-Json
$recoverySources = if ($previous.recoverySources) { $previous.recoverySources } else { $previous.sources }
function RecordFile([string]$RelativePath) {
    $item = Get-Item -LiteralPath (Join-Path $taskRoot $RelativePath)
    return [ordered]@{path=$RelativePath.Replace('\','/');bytes=$item.Length;sha256=(Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
}
function SameJson($Left, $Right) {
    return ($Left | ConvertTo-Json -Depth 24 -Compress) -ceq ($Right | ConvertTo-Json -Depth 24 -Compress)
}
foreach ($source in $recoverySources) {
    if ($source.path -in @('tools/run-machine-crash-audit.ps1','tools/record-assembly-crash-validation.ps1')) { continue }
    if ((RecordFile $source.path).sha256 -ne $source.sha256) { throw "Recovery implementation changed since the recorded process kills: $($source.path)" }
}
$cases = @()
foreach ($target in @('248','233')) {
    foreach ($machine in @('depot','belt','deployer','spout')) {
        foreach ($stage in @('prepared','confirmed','applied')) {
            $prefix = "artifacts/assembly-crash-final-$machine-$stage-$target-$RunRevision-$EvidenceDate"
            $termination = Get-Content -LiteralPath (Join-Path $taskRoot "$prefix-termination.json") -Raw | ConvertFrom-Json
            $state = Get-Content -LiteralPath (Join-Path $taskRoot "$prefix-state.json") -Raw | ConvertFrom-Json
            $killed = Get-Content -LiteralPath (Join-Path $taskRoot "$prefix-killed.log") -Raw
            $recovered = Get-Content -LiteralPath (Join-Path $taskRoot "$prefix-recovered.log") -Raw
            $runner = Get-Content -LiteralPath (Join-Path $taskRoot "$prefix-runner.log") -Raw
            $expected = if ($stage -eq 'applied') { 10 } else { 11 }
            if ($machine -in @('deployer','spout')) { $expected++ }
            if (!$termination.forcedTermination -or $termination.neoForge -ne "21.1.$target" -or $termination.machine -ne $machine -or
                $termination.stage -ne $stage -or $state.stage -ne $stage -or $state.type -ne $machine -or $state.entries -ne 1 -or
                $termination.run -ne $state.run -or $termination.entry -ne $state.entry -or $state.maximum -ne 1) { throw "Invalid termination/state identity: $prefix" }
            $argumentFile = Join-Path $taskRoot 'artifacts/machine-crash-build/moddev/serverRunProgramArgs.txt'
            if ($termination.verifiedArgumentFile -ne $argumentFile -or $termination.pid -le 0 -or
                [DateTimeOffset]::Parse($termination.processCreatedAt) -ge [DateTimeOffset]::Parse($termination.killedAt)) { throw "Invalid isolated JVM identity/time: $prefix" }
            $marker = "[MACHINECRASHAUDIT] KILL_READY pid=$($termination.pid) entry=$($termination.entry) run=$($termination.run)"
            if (!$killed.Contains($marker) -or $killed.Contains('[CREATEAUDIT] FAILED') -or $killed.Contains('[CREATEAUDIT] COMPLETE') -or
                $killed.Contains('Stopping server') -or $killed.Contains('BUILD SUCCESSFUL')) { throw "Missing actual kill or unexpected normal shutdown: $prefix" }
            if (!$recovered.Contains("[ASSEMBLYCRASHAUDIT] COMPLETE $expected checks type=$machine stage=$stage") -or
                [regex]::Matches($recovered,'\[ASSEMBLYCRASHAUDIT\] PASS ').Count -ne $expected -or $recovered.Contains('[CREATEAUDIT] FAILED') -or
                !$recovered.Contains('BUILD SUCCESSFUL') -or !$runner.Contains('PASS actual native machine process kill and durable evidence recovery.')) { throw "Recovery failed/incomplete: $prefix" }
            if ($state.savedItems.Count -ne 1 -or $state.savedItems[0].id -ne 'minecraft:prismarine_shard' -or
                $state.savedItems[0].components.'create:sequenced_assembly'.step -ne 1 -or $state.evidence.assembly.step -ne 1 -or
                $state.evidence.assembly.finalOutputs.Count -ne 1 -or $state.evidence.assembly.finalOutputs[0].count -ne 2) { throw "Incomplete exact assembly state: $prefix" }
            if ($state.evidence.recipeId -ne 'citizens_audit:assembly_crash' -or $state.evidence.recipeRevision -ne $state.revision -or
                $state.evidence.attribution.machineId -ne $state.machine -or $state.evidence.attribution.beneficiaryId -ne $state.actor -or
                !(SameJson $state.evidence.consumedInput $state.savedItems[0]) -or
                !(SameJson $state.evidence.assembly.finalOutputs $state.evidence.rolledOutputs)) { throw "Assembly observation identity/input/output mismatch: $prefix" }
            if ($stage -eq 'prepared') {
                if (($state.liveItems | ConvertTo-Json -Depth 12 -Compress) -ne ($state.savedItems | ConvertTo-Json -Depth 12 -Compress)) { throw "Prepared items already changed: $prefix" }
                if (!(SameJson $state.liveTool $state.savedTool) -or !(SameJson $state.liveFluid $state.savedFluid)) { throw "Prepared tool/fluid already changed: $prefix" }
            } elseif (!(SameJson $state.liveItems $state.evidence.assembly.finalOutputs)) { throw "Exact native final storage missing: $prefix" }
            if ($machine -eq 'deployer' -and ($state.savedTool.count -ne 3 -or ($stage -ne 'prepared' -and $state.liveTool.count -ne 2))) { throw "Native tool state mismatch: $prefix" }
            if ($machine -eq 'spout' -and ($state.savedFluid.amount -ne 300 -or ($stage -ne 'prepared' -and $state.liveFluid.amount -ne 200))) { throw "Native tank state mismatch: $prefix" }
            $cases += [ordered]@{neoForge="21.1.$target";machine=$machine;stage=$stage;recoveryChecks=$expected;
                killed=(RecordFile "$prefix-killed.log");killedStderr=(RecordFile "$prefix-killed-stderr.log");recovered=(RecordFile "$prefix-recovered.log");
                runner=(RecordFile "$prefix-runner.log");termination=(RecordFile "$prefix-termination.json");savedAndLiveState=(RecordFile "$prefix-state.json")}
        }
    }
}
$sources = @(
    'tools/create-runtime-audit/net/yuuniverse/interactions/AssemblyCrashRuntimeAudit.java',
    'tools/create-runtime-audit/net/yuuniverse/interactions/MachineCrashRuntimeAudit.java',
    'tools/create-runtime-audit/net/yuuniverse/interactions/CreateRuntimeAudit.java',
    'tools/create-runtime-audit/net/yuuniverse/interactions/AssemblyRuntimeAudit.java',
    'tools/create-runtime-audit.gradle','tools/prepare-create-audit.ps1','tools/run-machine-crash-audit.ps1',
    'tools/record-assembly-crash-validation.ps1',
    'neoforge/src/main/java/net/citizensnpcs/util/CreateMachineEvidence.java',
    'neoforge/src/main/java/net/citizensnpcs/util/CraftQuestEvidence.java',
    'neoforge/src/main/java/net/citizensnpcs/util/QuestEvidenceJournal.java'
) | ForEach-Object { RecordFile $_ }
$recoveryCount = 0
foreach ($case in $cases) { $recoveryCount += $case.recoveryChecks }
$ordinary = [ordered]@{}
$native = [ordered]@{}
$packages = @()
foreach ($target in @('233','248')) {
    $summaryPath = "artifacts/assembly-recovery-final-core-tests-$target-$RegressionDate.json"
    $summary = Get-Content -LiteralPath (Join-Path $taskRoot $summaryPath) -Raw | ConvertFrom-Json
    $buildPath = "artifacts/assembly-recovery-final-core-$target-$RegressionDate.log"
    if ($summary.tests -ne 767 -or $summary.failures -ne 0 -or $summary.errors -ne 0 -or $summary.skipped -ne 0 -or
        !(Get-Content -LiteralPath (Join-Path $taskRoot $buildPath) -Raw).Contains('BUILD SUCCESSFUL')) { throw "Core regression failed: $target" }
    $ordinary["21.1.$target"] = [ordered]@{summary=$summary;summaryFile=(RecordFile $summaryPath);build=(RecordFile $buildPath)}
    $nativePath = "artifacts/assembly-recovery-final-native-$target-$RegressionDate.log"
    $log = Get-Content -LiteralPath (Join-Path $taskRoot $nativePath) -Raw
    $counts = [ordered]@{CREATEAUDIT=54;CREATEBATCHAUDIT=23;CREATEPRESSAUDIT=16;CREATETRANSPORTAUDIT=21;CREATEBASINAUDIT=23;CREATEFLUIDAUDIT=28;CREATEDEPLOYERAUDIT=26;CREATEFANAUDIT=31;CREATEASSEMBLYAUDIT=41}
    if (!$log.Contains('BUILD SUCCESSFUL') -or $log.Contains('[CREATEAUDIT] FAILED')) { throw "Native regression failed: $target" }
    foreach ($audit in $counts.Keys) {
        if (!$log.Contains("[$audit] COMPLETE $($counts[$audit]) checks") -or [regex]::Matches($log,"\[$audit\] PASS ").Count -ne $counts[$audit]) { throw "Incomplete native checks: $target/$audit" }
    }
    $native["21.1.$target"] = [ordered]@{checks=263;suites=$counts;log=(RecordFile $nativePath)}
    $packageDirectory = "artifacts/assembly-recovery-release-$target-jars-$RegressionDate"
    $jars = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot $packageDirectory) -Filter '*.jar')
    if ($jars.Count -ne 3) { throw "Unexpected release archive count: $target" }
    foreach ($jar in $jars) {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
        try {
            $paths = @($archive.Entries.FullName)
            if ($paths | Where-Object { $_ -match '(RuntimeAudit|MachineRemainderItem|^com/simibubi/|^dev/ftb/|^dev/latvian/|^org/yuuniverse/economy/)' }) { throw "Audit/provider code in release: $($jar.Name)" }
            if ($jar.Name.StartsWith('citizens-') -and ($paths | Where-Object { $_ -like 'net/yuuniverse/interactions/*' })) { throw 'Interactions classes in Citizens archive' }
            if ($jar.Name.StartsWith('yuuniverse-interactions-') -and ($paths | Where-Object { $_ -like 'net/citizensnpcs/*' })) { throw 'Citizens classes in Interactions archive' }
        } finally { $archive.Dispose() }
        $packages += [ordered]@{neoForge="21.1.$target";archive=(RecordFile "$packageDirectory/$($jar.Name)");probeAndForeignProviderCodeAbsent=$true;independentModContents=$true}
    }
}
$dialoguePath = "artifacts/assembly-recovery-final-dialogue-tests-248-$RegressionDate.json"
$dialogue = Get-Content -LiteralPath (Join-Path $taskRoot $dialoguePath) -Raw | ConvertFrom-Json
$dialogueLog = "artifacts/assembly-recovery-final-dialogue-248-$RegressionDate.log"
if ($dialogue.tests -ne 100 -or $dialogue.failures -ne 0 -or $dialogue.errors -ne 0 -or $dialogue.skipped -ne 0 -or
    !(Get-Content -LiteralPath (Join-Path $taskRoot $dialogueLog) -Raw).Contains('BUILD SUCCESSFUL')) { throw 'Dialogue regression failed' }
$isolationPrefix = "artifacts/assembly-crash-isolated-cache-spout-prepared-248-$RegressionDate"
$isolationTermination = Get-Content -LiteralPath (Join-Path $taskRoot "$isolationPrefix-termination.json") -Raw | ConvertFrom-Json
$isolationState = Get-Content -LiteralPath (Join-Path $taskRoot "$isolationPrefix-state.json") -Raw | ConvertFrom-Json
$isolationKilled = Get-Content -LiteralPath (Join-Path $taskRoot "$isolationPrefix-killed.log") -Raw
$isolationRecovered = Get-Content -LiteralPath (Join-Path $taskRoot "$isolationPrefix-recovered.log") -Raw
$isolationRunner = Get-Content -LiteralPath (Join-Path $taskRoot "$isolationPrefix-runner.log") -Raw
if (!$isolationTermination.forcedTermination -or $isolationTermination.stage -ne 'prepared' -or $isolationTermination.machine -ne 'spout' -or
    $isolationTermination.neoForge -ne '21.1.248' -or $isolationTermination.run -ne $isolationState.run -or
    $isolationTermination.entry -ne $isolationState.entry -or
    !$isolationKilled.Contains("[MACHINECRASHAUDIT] KILL_READY pid=$($isolationTermination.pid) entry=$($isolationTermination.entry) run=$($isolationTermination.run)") -or
    $isolationKilled.Contains('Stopping server') -or $isolationKilled.Contains('[CREATEAUDIT] FAILED') -or
    !$isolationRecovered.Contains('[ASSEMBLYCRASHAUDIT] COMPLETE 12 checks type=spout stage=prepared') -or
    [regex]::Matches($isolationRecovered,'\[ASSEMBLYCRASHAUDIT\] PASS ').Count -ne 12 -or
    !$isolationRecovered.Contains('BUILD SUCCESSFUL') -or $isolationRecovered.Contains('[CREATEAUDIT] FAILED') -or
    !$isolationRunner.Contains('PASS actual native machine process kill and durable evidence recovery.')) { throw 'Isolated-cache process-kill regression failed' }
$isolationFiles = @('termination.json','state.json','killed.log','killed-stderr.log','recovered.log','runner.log') | ForEach-Object { RecordFile "$isolationPrefix-$_" }
$manifest = [ordered]@{createdAt=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'China Standard Time').ToString('o');timezone='UTC+08:00';
    recoveryEvidenceDate=$EvidenceDate;regressionDate=$RegressionDate;
    provider=(RecordFile 'artifacts/create-provider-pinned.jar');providerVersion='6.0.10-mc1.21.1-performance-v6';
    recoverySources=$recoverySources;sources=$sources;actualProcessKills=$cases.Count;recoveryChecks=$recoveryCount;cases=$cases;
    ordinaryTests=$ordinary;nativeRegression=$native;packages=$packages;
    isolatedCacheRunner=[ordered]@{actualProcessKills=1;recoveryChecks=12;files=$isolationFiles};
    dialogueTests=[ordered]@{summary=$dialogue;summaryFile=(RecordFile $dialoguePath);log=(RecordFile $dialogueLog)};
    unchangedProductionRevision='b10515f';productionActivated=$false;
    remaining=@('Other machine paths and arbitrary storage-interior kill points','Repeatable/party/offline/reconnect and financial backup matrix',
        'Contraption copying and original legacy provenance','Logging operations and supported Paradigm inbound formatting','Physical VR/non-VR integrated migration')}
$manifest | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath (Join-Path $taskRoot "docs/create-assembly-recovery-validation-$EvidenceDate.json") -Encoding utf8
Write-Output "Verified $($cases.Count) actual kills and $($manifest.recoveryChecks) recovery checks."
