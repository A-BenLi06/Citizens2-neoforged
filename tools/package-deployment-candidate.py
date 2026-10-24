"""Assemble the pinned private deployment candidate; never touch a server."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tomllib
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def archive_info(path, mod_id):
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError(f'Corrupt archive: {path}')
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError(f'Duplicate archive entries: {path}')
        forbidden = [n for n in names if n.endswith('.class') and (
            n.startswith('net/citizensnpcs/audit/') or 'RuntimeAudit' in n
            or n.endswith('Test.class'))]
        if forbidden:
            raise ValueError(f'Fixture classes in {path}: {forbidden}')
        descriptor = next(n for n in ('META-INF/neoforge.mods.toml',
                                      'META-INF/mods.toml') if n in names)
        metadata = tomllib.loads(archive.read(descriptor).decode('utf-8-sig'))
        mods = metadata['mods']
        if [m['modId'] for m in mods] != [mod_id]:
            raise ValueError(f'Unexpected mod IDs in {path}: {mods}')
        version = mods[0]['version']
        if version == '${file.jarVersion}':
            manifest = archive.read('META-INF/MANIFEST.MF').decode()
            version = next(line.split(': ', 1)[1] for line in manifest.splitlines()
                           if line.startswith('Implementation-Version: '))
        if mod_id == 'citizens' and any(n.startswith('net/yuuniverse/interactions/') for n in names):
            raise ValueError('Interactions leaked into Citizens')
        if mod_id == 'interactions' and any(n.startswith('net/citizensnpcs/') for n in names):
            raise ValueError('Citizens leaked into Interactions')
        return {'version': version, 'descriptor': descriptor,
                'dependencies': metadata.get('dependencies', {}).get(mod_id, [])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    bundle = output.with_suffix('.zip')
    if output.exists() or bundle.exists():
        raise ValueError('Use a new output directory and ZIP path')
    spec_path = ROOT / 'docs/deployment-candidate-2026-10-24.json'
    spec = json.loads(spec_path.read_text(encoding='utf-8'))
    changed = subprocess.check_output([
        'git', 'diff', spec['citizensSourceCommit'], '--', 'neoforge/src',
        'neoforge/build.gradle', 'neoforge/gradle.properties',
        'main/src/main/resources/plugin.yml'], cwd=ROOT)
    if changed:
        raise ValueError('Production sources differ from the pinned checkpoint')
    prepared = []
    for item in spec['mods']:
        source = (ROOT / item['source']).resolve()
        if digest(source) != item['sha256']:
            raise ValueError(f'Input hash mismatch: {source}')
        info = archive_info(source, item['modId'])
        if info['version'] != item['version']:
            raise ValueError(f'Input version mismatch: {source}')
        prepared.append((item, source, info))
    output.mkdir(parents=True)
    records = []
    for item, source, info in prepared:
        destination = output / 'mods' / item['filename']
        destination.parent.mkdir(exist_ok=True)
        shutil.copy2(source, destination)
        if digest(destination) != item['sha256']:
            raise ValueError(f'Copy hash mismatch: {destination}')
        records.append({**item, **info, 'path': destination.relative_to(output).as_posix(),
                        'bytes': destination.stat().st_size})
    for source_name in spec['supportingFiles']:
        source = ROOT / source_name
        destination = output / 'reference' / source.name
        destination.parent.mkdir(exist_ok=True)
        shutil.copy2(source, destination)
    for source_name in spec['sourceArchives']:
        source = ROOT / source_name
        destination = output / 'sources' / source.name
        destination.parent.mkdir(exist_ok=True)
        shutil.copy2(source, destination)
    # Include the separate dialogue source set, which main's sourcesJar omits.
    dialogue_files = subprocess.check_output([
        'git', 'ls-files', '-z', 'neoforge/src/dialogue'], cwd=ROOT).decode().split('\0')
    with zipfile.ZipFile(output / 'sources/interactions-sources.zip', 'w',
                         zipfile.ZIP_DEFLATED) as archive:
        for name in filter(None, dialogue_files):
            archive.write(ROOT / name, name)
    shutil.copy2(ROOT / 'docs/deployment-candidate-2026-10-24.md', output / 'DEPLOYMENT.md')
    baseline = json.loads((ROOT / spec['providerInventory']).read_text(encoding='utf-8'))
    inventory = baseline['mods']
    replaced = {item['modId']: item for item in records}
    selected = []
    for provider in inventory:
        replacement = next((replaced[mid] for mid in provider['modIds'] if mid in replaced), None)
        selected.append({'filename': replacement['filename'] if replacement else Path(provider['path']).name,
                         'sha256': replacement['sha256'] if replacement else provider['sha256'],
                         'modIds': provider['modIds'], 'included': replacement is not None})
    manifest = {'createdAt': datetime.now().astimezone().isoformat(),
                'target': spec['target'], 'privateLocalCandidate': True,
                'citizensSourceCommit': spec['citizensSourceCommit'],
                'verification': 'Build and archive/hash inspection only; no new gameplay run.',
                'mods': records, 'selectedProviderInventory': selected}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    checksums = []
    for path in sorted(p for p in output.rglob('*') if p.is_file()):
        checksums.append(f'{digest(path)}  {path.relative_to(output).as_posix()}')
    (output / 'SHA256SUMS.txt').write_text('\n'.join(checksums) + '\n', encoding='utf-8')
    with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(p for p in output.rglob('*') if p.is_file()):
            archive.write(path, path.relative_to(output))
    with zipfile.ZipFile(bundle) as archive:
        if archive.testzip() is not None:
            raise ValueError('Bundle integrity failure')
    bundle.with_suffix('.zip.sha256').write_text(f'{digest(bundle)}  {bundle.name}\n', encoding='utf-8')
    print(json.dumps({'directory': str(output), 'zip': str(bundle),
                      'sha256': digest(bundle), 'mods': len(records)}, indent=2))


if __name__ == '__main__':
    main()
