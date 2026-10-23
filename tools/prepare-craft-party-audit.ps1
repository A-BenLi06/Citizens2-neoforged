param(
    [Parameter(Mandatory)][string]$ProviderDirectory,
    [Parameter(Mandatory)][string]$Fixture
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$artifactRoot=[IO.Path]::GetFullPath((Join-Path $taskRoot 'artifacts'))+[IO.Path]::DirectorySeparatorChar
$fixturePath=[IO.Path]::GetFullPath((Join-Path $taskRoot $Fixture))
if (!$fixturePath.StartsWith($artifactRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Craft party fixture must be inside artifacts' }
if (Test-Path -LiteralPath $fixturePath) { throw 'Craft party fixture must be fresh' }
$providerNames=@('architectury-13.0.11-neoforge.jar','ftb-library-neoforge-2101.1.37.jar',
    'ftb-quests-neoforge-2101.1.36.jar','ftb-teams-neoforge-2101.1.11.jar')
$providers=foreach($name in $providerNames) { Get-Item -LiteralPath (Join-Path $ProviderDirectory $name) }
New-Item -ItemType Directory -Path (Join-Path $fixturePath 'mods')|Out-Null
foreach($provider in $providers) { Copy-Item -LiteralPath $provider.FullName -Destination (Join-Path $fixturePath 'mods') }
@('server-ip=127.0.0.1','server-port=25646','online-mode=false','level-name=craft-world',
  'level-type=minecraft:flat','generate-structures=false','spawn-protection=0',
  'view-distance=2','simulation-distance=2',
  'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}')|
    Set-Content -LiteralPath (Join-Path $fixturePath 'server.properties') -Encoding utf8NoBOM
'eula=true'|Set-Content -LiteralPath (Join-Path $fixturePath 'eula.txt') -Encoding ascii
'citizens-craft-recovery-audit'|Set-Content -LiteralPath (Join-Path $fixturePath 'craft-recovery-fixture.txt') -Encoding ascii
Write-Output "Prepared $fixturePath"
