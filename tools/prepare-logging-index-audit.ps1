param(
    [Parameter(Mandatory)][string]$FixtureDirectory,
    [Parameter(Mandatory)][string]$ProviderJar,
    [string]$SourceFixture = (Join-Path (Split-Path $PSScriptRoot -Parent) 'artifacts/logging-shutdown-normal-regression-server'),
    [switch]$DisableIndexes
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$artifactRoot=[IO.Path]::GetFullPath((Join-Path $taskRoot 'artifacts'))+[IO.Path]::DirectorySeparatorChar
$fixture=[IO.Path]::GetFullPath($FixtureDirectory)
$source=[IO.Path]::GetFullPath($SourceFixture)
foreach($path in @($fixture,$source)) {
    if(!$path.StartsWith($artifactRoot,[StringComparison]::OrdinalIgnoreCase)) {throw 'Index audit paths must be inside task artifacts'}
}
if(Test-Path -LiteralPath $fixture) {throw 'Index acceptance requires a fresh fixture directory'}
if($fixture.StartsWith($source+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Index fixture must not be inside its source fixture'
}
$provider=(Get-Item -LiteralPath $ProviderJar).FullName
if((Get-Content -LiteralPath (Join-Path $source 'logging-audit-fixture.txt') -Raw).Trim() -ne 'citizens-logging-audit') {throw 'Missing source fixture marker'}
foreach($relative in @('logging-world/session.lock','database.db','logging-world/neoprotect/neoprotect.mv.db','logging-world/yuuniverse_economy/economy.db')) {
    $guard=[IO.File]::Open((Join-Path $source $relative),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)
    $guard.Dispose()
}
Copy-Item -LiteralPath $source -Destination $fixture -Recurse
$old=@(Get-ChildItem -LiteralPath (Join-Path $fixture 'mods') -Filter 'grieflogger-*.jar' -File)
if($old.Count -ne 1) {throw 'Expected exactly one original provider'}
Remove-Item -LiteralPath $old[0].FullName
Copy-Item -LiteralPath $provider -Destination (Join-Path $fixture 'mods')
$config=Join-Path $fixture 'config/grieflogger/grieflogger.toml'
$text=Get-Content -LiteralPath $config -Raw
$pattern='(?m)^(\s*useIndexes\s*=\s*)(true|false)\s*$'
if([regex]::Matches($text,$pattern).Count -ne 1) {throw 'Unknown index configuration template'}
$enabled=(!$DisableIndexes).ToString().ToLowerInvariant()
$disabled=$DisableIndexes.IsPresent.ToString().ToLowerInvariant()
[regex]::Replace($text,$pattern,('${1}'+$enabled)) | Set-Content -LiteralPath $config -Encoding utf8NoBOM
@'
from pathlib import Path
import json,sqlite3,sys
fixture=Path(sys.argv[1]);path=fixture/'database.db'
assert not any(Path(str(path)+s).exists() for s in ['-wal','-shm','-journal'])
tables=['users','usernames','levels','materials','entities','blocks','chats','commands','containers','items','sessions']
with sqlite3.connect(path) as db:
    assert db.execute('PRAGMA quick_check').fetchall()==[('ok',)]
    if sys.argv[2]=='true':
        # Only the copied fixture: emulate data predating all optional indexes.
        assert db.execute("SELECT tbl_name,sql FROM sqlite_master WHERE name='coordinates'").fetchall()==[('blocks','CREATE INDEX coordinates ON blocks (x, y, z)')]
        db.execute('DROP INDEX coordinates');db.commit()
    snapshot={table:[[{'blob':v.hex()} if isinstance(v,bytes) else v for v in row] for row in db.execute('SELECT '+('rowid,time,user,level,x,y,z,type,action' if table=='blocks' else 'rowid,*')+' FROM '+table+' ORDER BY rowid')] for table in tables}
with (fixture/'logging-index-original.json').open('x',encoding='utf-8') as out:
    json.dump(snapshot,out,ensure_ascii=False,separators=(',',':'))
'@ | python -B - $fixture $disabled
if($LASTEXITCODE -ne 0){throw 'Stopped original-row snapshot failed'}
Write-Output "Prepared $fixture with indexes=$enabled"
