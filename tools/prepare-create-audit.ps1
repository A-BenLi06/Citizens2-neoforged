param(
    [Parameter(Mandatory = $true)][string]$CreateJar,
    [string]$ProviderDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/selected-integrations-2026-10-12'),
    [ValidateSet('depot','belt','deployer','spout')][string]$AssemblyCrashMachine,
    [switch]$BasinCrash,
    [string]$Fixture
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
if ($AssemblyCrashMachine -and $BasinCrash) { throw 'Select one native machine family' }
if (!$Fixture) { $Fixture = Join-Path $taskRoot 'artifacts/create-audit-server' }
$fixture = [IO.Path]::GetFullPath($Fixture)
$artifactRoot = [IO.Path]::GetFullPath((Join-Path $taskRoot 'artifacts')) + [IO.Path]::DirectorySeparatorChar
if (!$fixture.StartsWith($artifactRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Create fixture must be inside task artifacts' }
New-Item -ItemType Directory -Force (Join-Path $fixture 'mods'), (Join-Path $fixture 'compile-libs'),
    (Join-Path $fixture 'kubejs/server_scripts') | Out-Null
$provider = Get-Item -LiteralPath $CreateJar
Copy-Item -LiteralPath $provider.FullName -Destination (Join-Path $fixture 'mods/create.jar')
$archive = [System.IO.Compression.ZipFile]::OpenRead($provider.FullName)
try {
    foreach ($entry in $archive.Entries | Where-Object { $_.FullName -like 'META-INF/jarjar/*.jar' }) {
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $fixture "compile-libs/$($entry.Name)"), $true)
    }
} finally { $archive.Dispose() }
foreach ($name in @('ftb-library-neoforge-2101.1.37.jar','ftb-quests-neoforge-2101.1.36.jar','ftb-teams-neoforge-2101.1.11.jar',
    'kubejs-neoforge-2101.7.2-build.377.jar','rhino-2101.2.8-build.91.jar')) {
    Copy-Item -LiteralPath (Join-Path $ProviderDirectory $name) -Destination (Join-Path $fixture 'mods')
}
$architectury = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/ecosystem-2026-10-13/server-smoke/mods/architectury-13.0.11-neoforge.jar'
Copy-Item -LiteralPath $architectury -Destination (Join-Path $fixture 'mods')
@('server-ip=127.0.0.1','server-port=25629','online-mode=false','level-name=create-world',
  'level-type=minecraft:flat','generate-structures=false','spawn-protection=0',
  'view-distance=2','simulation-distance=2',
  'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}') |
    Set-Content -LiteralPath (Join-Path $fixture 'server.properties') -Encoding utf8
'eula=true' | Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8
'citizens-create-audit' | Set-Content -LiteralPath (Join-Path $fixture 'create-audit-fixture.txt') -Encoding utf8
@'
ServerEvents.recipes(event => {
    event.custom({ type: 'create:milling', ingredients: [{ item: 'minecraft:stone' }], processing_time: 40,
        results: [{ id: 'minecraft:paper', count: 3 }, { id: 'minecraft:diamond', chance: 0.25 }] }).id('citizens_audit:create_milling')
})
'@ | Set-Content -LiteralPath (Join-Path $fixture 'kubejs/server_scripts/citizens_create_audit.js') -Encoding utf8
$assemblyScript = Join-Path $fixture 'kubejs/server_scripts/citizens_assembly_crash.js'
if ($AssemblyCrashMachine) {
    $step = switch ($AssemblyCrashMachine) {
        'deployer' { @{type='create:deploying';ingredients=@(@{item='minecraft:prismarine_shard'},@{item='minecraft:flint'});results=@(@{id='minecraft:prismarine_shard'})} }
        'spout' { @{type='create:filling';ingredients=@(@{item='minecraft:prismarine_shard'},@{type='fluid_stack';fluid='minecraft:water';amount=100});results=@(@{id='minecraft:prismarine_shard'})} }
        default { @{type='create:pressing';ingredients=@(@{item='minecraft:prismarine_shard'});results=@(@{id='minecraft:prismarine_shard'})} }
    }
    $recipe = @{type='create:sequenced_assembly';ingredient=@{item='minecraft:snowball'};transitional_item=@{id='minecraft:prismarine_shard'};loops=1;
        sequence=@(@{type='create:pressing';ingredients=@(@{item='minecraft:prismarine_shard'});results=@(@{id='minecraft:prismarine_shard'})},$step);
        results=@(@{id='minecraft:paper';count=2;components=@{'minecraft:custom_name'='"Assembly crash output"'}})}
    $json = $recipe | ConvertTo-Json -Depth 10 -Compress
    "ServerEvents.recipes(event => { event.custom($json).id('citizens_audit:assembly_crash') })" | Set-Content -LiteralPath $assemblyScript -Encoding utf8
} elseif (Test-Path -LiteralPath $assemblyScript) { Remove-Item -LiteralPath $assemblyScript }
$basinScript = Join-Path $fixture 'kubejs/server_scripts/citizens_basin_crash.js'
if ($BasinCrash) {
@'
ServerEvents.recipes(event => {
    event.custom({ type: 'create:mixing', ingredients: [
        { item: 'minecraft:snowball' }, { item: 'minecraft:snowball' }, { item: 'minecraft:flint' },
        { type: 'neoforge:single', fluid: 'minecraft:water', amount: 250 }],
        results: [{ id: 'minecraft:paper', count: 2, components: { 'minecraft:custom_name': '"Basin crash output"' } },
            { id: 'minecraft:lava', amount: 100 }], processingTime: 20 }).id('citizens_audit:basin_crash')
})
'@ | Set-Content -LiteralPath $basinScript -Encoding utf8
} elseif (Test-Path -LiteralPath $basinScript) { Remove-Item -LiteralPath $basinScript }
Write-Output "Prepared $fixture"
