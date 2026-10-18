param(
    [string]$ProviderDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/selected-integrations-2026-10-12'),
    [string]$ParadigmJar = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/ecosystem-2026-10-13/server-smoke/mods/Paradigm-neoforge-1.21.1-2.5.0.jar'),
    [string]$ArchitecturyJar = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/ecosystem-2026-10-13/server-smoke/mods/architectury-13.0.11-neoforge.jar'),
    [string]$OriginalItemFixtureDirectory,
    [switch]$WithKubeJS
)
$ErrorActionPreference = 'Stop'
$fixture = Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/quest-audit-server'
New-Item -ItemType Directory -Force (Join-Path $fixture 'mods') | Out-Null
foreach ($name in @('ftb-library-neoforge-2101.1.37.jar','ftb-quests-neoforge-2101.1.36.jar','ftb-teams-neoforge-2101.1.11.jar','rsitemeditor-1.0.1.jar','kotlinforforge-5.12.0-all.jar')) {
    Copy-Item -LiteralPath (Join-Path $ProviderDirectory $name) -Destination (Join-Path $fixture 'mods')
}
Copy-Item -LiteralPath $ParadigmJar -Destination (Join-Path $fixture 'mods')
Copy-Item -LiteralPath $ArchitecturyJar -Destination (Join-Path $fixture 'mods')
if ($OriginalItemFixtureDirectory) {
    foreach ($jar in Get-ChildItem -LiteralPath (Join-Path $OriginalItemFixtureDirectory 'mods') -Filter '*.jar') {
        Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $fixture 'mods')
    }
    Copy-Item -LiteralPath (Join-Path $OriginalItemFixtureDirectory 'config/interactions/items.yml') -Destination (Join-Path $fixture 'original-items.yml')
    Copy-Item -LiteralPath (Join-Path $OriginalItemFixtureDirectory 'config/interactions/item-aliases.yml') -Destination (Join-Path $fixture 'original-item-aliases.yml')
}
if ($WithKubeJS) {
    foreach ($name in @('kubejs-neoforge-2101.7.2-build.377.jar','rhino-2101.2.8-build.91.jar')) {
        Copy-Item -LiteralPath (Join-Path $ProviderDirectory $name) -Destination (Join-Path $fixture 'mods')
    }
    $recipeScripts = Join-Path $fixture 'kubejs/server_scripts'
    New-Item -ItemType Directory -Force $recipeScripts | Out-Null
    @'
ServerEvents.recipes(event => {
    event.shapeless(Item.of('minecraft:paper', 3), ['minecraft:stone']).id('citizens_audit:kubejs_recipe')
})
'@ | Set-Content -LiteralPath (Join-Path $recipeScripts 'citizens_recipe_audit.js') -Encoding utf8
}
@('server-ip=127.0.0.1','server-port=25628','online-mode=false','level-name=quest-world',
  'level-type=minecraft:flat','generate-structures=false','spawn-protection=0',
  'view-distance=2','simulation-distance=2','sync-chunk-writes=false',
  'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}') |
    Set-Content -LiteralPath (Join-Path $fixture 'server.properties') -Encoding utf8
'eula=true' | Set-Content -LiteralPath (Join-Path $fixture 'eula.txt') -Encoding utf8
'citizens-quest-audit' | Set-Content -LiteralPath (Join-Path $fixture 'quest-audit-fixture.txt') -Encoding utf8
New-Item -ItemType Directory -Force (Join-Path $fixture 'config'), (Join-Path $fixture 'defaultconfigs') | Out-Null
New-Item -ItemType Directory -Force (Join-Path $fixture 'config/citizens') | Out-Null
'{"schema":1,"exports":[{"key":"audit_craft_progress","kind":"FTB_TASK_PROGRESS","taskId":"10403"},{"key":"audit_craft_completed","kind":"FTB_TASK_COMPLETED","taskId":"10403"},{"key":"audit_missing_balance","kind":"ECONOMY_BALANCE","currencyId":"audit"}]}' |
    Set-Content -LiteralPath (Join-Path $fixture 'config/citizens/placeholder-exports.json') -Encoding utf8
$questChapters = Join-Path $fixture 'config/ftbquests/quests/chapters'
New-Item -ItemType Directory -Force $questChapters | Out-Null
@'
{
    filename: "0000000000010401"
    id: "0000000000010401"
    quests: [{
        id: "0000000000010402"
        tasks: [{
            id: "0000000000010403"
            type: "item"
            item: { count: 1, id: "minecraft:paper" }
            count: 100L
            consume_items: false
            only_from_crafting: true
        }]
        x: 0.0d
        y: 0.0d
    }]
}
'@ | Set-Content -LiteralPath (Join-Path $questChapters '0000000000010401.snbt') -Encoding utf8
@'
{
    filename: "0000000000010701"
    id: "0000000000010701"
    quests: [{
        id: "0000000000010702"
        invisible: true
        tasks: [{ id: "0000000000010703", type: "custom", max_progress: 100L }]
        x: 0.0d
        y: 0.0d
    }]
}
'@ | Set-Content -LiteralPath (Join-Path $questChapters '0000000000010701.snbt') -Encoding utf8
'permissionHandler = "paradigm:internal"' | Set-Content -LiteralPath (Join-Path $fixture 'config/neoforge-server.toml') -Encoding utf8
'permissionHandler = "paradigm:internal"' | Set-Content -LiteralPath (Join-Path $fixture 'defaultconfigs/neoforge-server.toml') -Encoding utf8
Write-Output "Prepared $fixture"
