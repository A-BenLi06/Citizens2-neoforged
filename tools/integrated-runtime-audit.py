"""Prepare and run packaged provider coexistence audits in a fresh synthetic world."""
from datetime import datetime
from pathlib import Path
import argparse
import ctypes
import hashlib
import json
import os
import re
import shutil
import socket
import sqlite3
import subprocess
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PHASES = ["xmod", "quest", "logging", "logging-recover"]


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write(path, value):
    with Path(path).open("x", encoding="utf-8") as output:
        json.dump(value, output, indent=2, ensure_ascii=False)
        output.write("\n")


def evidence(path):
    path = Path(path).resolve()
    return dict(path=path.relative_to(ROOT).as_posix(), bytes=path.stat().st_size,
                sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def fixture_path(value):
    path = (ROOT / value).resolve()
    if not path.is_relative_to(ROOT / "artifacts"):
        raise ValueError("Integrated fixture must be inside task artifacts")
    return path


def checked(record):
    path = ROOT / record["path"]
    actual = evidence(path)
    if actual["sha256"] != record["sha256"].lower() or actual["bytes"] != record["bytes"]:
        raise ValueError("Pinned input changed: " + record["path"])
    return path


def mod_ids(path):
    with zipfile.ZipFile(path) as archive:
        for name in ["META-INF/neoforge.mods.toml", "META-INF/mods.toml"]:
            if name in archive.namelist():
                return [mod["modId"] for mod in tomllib.loads(archive.read(name).decode("utf-8"))["mods"]]
        if "fabric.mod.json" in archive.namelist():
            return [read_zip_json(archive, "fabric.mod.json")["id"]]
        if "META-INF/MANIFEST.MF" in archive.namelist():
            manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
            if re.search(r"(?mi)^FMLModType:\s*(LIBRARY|LANGPROVIDER|GAMELIBRARY)\s*$", manifest):
                return []
    # Bootstrap/service archives can supply nested mods without a top-level descriptor.
    # Retain their pinned bytes; loaded-provider inventory is captured by the server.
    return []


def prepare(fixture, link_providers=False):
    if (fixture / "integrated-audit-fixture.json").exists() or (fixture / "quest-world").exists():
        raise ValueError("Preserve existing integrated acceptance")
    if (fixture / "quest-audit-fixture.txt").read_text(encoding="utf-8-sig").strip() != "citizens-quest-audit":
        raise ValueError("Prepare a fresh explicit quest fixture first")
    original = ROOT / "artifacts/ordinary-client-acceptance-server"
    original_manifest = read(original / "acceptance-manifest.json")
    validation = read(ROOT / "docs/logging-entity-validation-2026-10-23.json")
    replacements = {}
    for record in validation["packages"]:
        if "21.1.248-build" in record["path"] and not record["path"].endswith("-sources.jar"):
            source = checked(record)
            replacements[mod_ids(source)[0]] = source
    logger = checked(validation["provider"])
    replacements["grieflogger"] = logger
    economy = ROOT / "artifacts/financial-backup-09c5de5beada4b8e9c79b727a1deb18b-server/mods/economy.jar"
    if evidence(economy)["sha256"] != "fc24deb2eefab0cb3eb87a3bb70a59897b9ad1a3b13f851ebb6f73994fdd75b7":
        raise ValueError("Expected the accepted Economy 0.5.9 candidate")
    replacements["yuuniverse_economy"] = economy
    selected = []
    replaced = set()
    for record in original_manifest["files"]:
        source = original / "mods" / record["name"]
        if evidence(source)["sha256"] != record["sha256"].lower():
            raise ValueError("Deferred fixture artifact changed: " + record["name"])
        ids = mod_ids(source)
        targets = set(ids).intersection(replacements)
        if targets:
            if len(ids) != 1 or len(targets) != 1:
                raise ValueError("Replacement must retain exactly one existing mod owner")
            owner = next(iter(targets))
            source = replacements[owner]
            replaced.add(owner)
        selected.append(source)
    if replaced != replacements.keys():
        raise ValueError("Full candidate did not replace every intended owner")
    selected_by_id = {}
    for source in selected:
        for owner in mod_ids(source):
            if owner in selected_by_id:
                raise ValueError("Duplicate selected mod ID: " + owner)
            selected_by_id[owner] = source
    for existing in (fixture / "mods").glob("*.jar"):
        ids = mod_ids(existing)
        if evidence(existing)["sha256"] not in {evidence(path)["sha256"] for path in selected} or not all(owner in selected_by_id and evidence(existing)["sha256"] == evidence(selected_by_id[owner])["sha256"] for owner in ids):
            raise ValueError("Fresh quest provider differs from the selected full stack: " + existing.name)
        existing.unlink()
    for source in selected:
        destination = fixture / "mods" / source.name
        if destination.exists():
            raise ValueError("Duplicate candidate filename: " + source.name)
        if link_providers:
            os.link(source, destination)
        else:
            shutil.copyfile(source, destination)
    compile_libs = fixture / "compile-libs"
    compile_libs.mkdir()
    for owner in ["create", "grieflogger"]:
        with zipfile.ZipFile(selected_by_id[owner]) as archive:
            metadata = read_zip_json(archive, "META-INF/jarjar/metadata.json")
            for entry in metadata["jars"]:
                target = compile_libs / Path(entry["path"]).name
                with target.open("xb") as output:
                    output.write(archive.read(entry["path"]))
    shutil.copyfile(original / "launch-args.txt", fixture / "launch-args.txt")
    text = (fixture / "server.properties").read_text(encoding="utf-8-sig")
    text = re.sub(r"(?m)^server-port=.*$", "server-port=25641", text)
    text = re.sub(r"(?m)^level-name=.*$", "level-name=integrated-world", text)
    text += "\nenable-rcon=false\nenable-query=false\n"
    (fixture / "server.properties").write_text(text, encoding="utf-8")
    shutil.copyfile(ROOT / "tools/xmod-runtime-audit/citizens_xmod_audit.js", fixture / "kubejs/server_scripts/citizens_xmod_audit.js")
    record = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00",
                  marker="citizens-integrated-audit", baseRevision=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                  port=25641, world="integrated-world", phases=PHASES,
                  providerStorage="immutable-hardlinks" if link_providers else "copies",
                  originalManifest=evidence(original / "acceptance-manifest.json"),
                  replacements={owner: evidence(path) for owner, path in replacements.items()},
                  releaseInputs={owner: evidence(selected_by_id[owner]) for owner in ["citizens", "interactions"]},
                  mods=[dict(**evidence(path), modIds=mod_ids(path)) for path in sorted((fixture / "mods").glob("*.jar"))],
                  inputs=[evidence(path) for folder in ["kubejs/server_scripts", "config/ftbquests", "config/citizens", "defaultconfigs"] for path in sorted((fixture / folder).rglob("*")) if path.is_file()])
    write(fixture / "integrated-audit-fixture.json", record)
    print("Prepared", len(record["mods"]), "packaged archives with current Citizens/Interactions, Economy and GriefLogger")


def read_zip_json(archive, path):
    return json.loads(archive.read(path))


def package(fixture, build):
    marker = read(fixture / "integrated-audit-fixture.json")
    if (fixture / "integrated-audit-candidate.json").exists():
        raise ValueError("Candidate packaging already completed")
    probes = []
    for owner, source_set, production_set, prefix in [("citizens", "mainProbe", "main", "net/citizensnpcs/audit/"), ("interactions", "dialogueProbe", "dialogue", "net/yuuniverse/interactions/")]:
        original = checked(marker["releaseInputs"][owner])
        output = build / "classes/java" / source_set
        classes = sorted(output.rglob("*.class"))
        if not classes:
            raise ValueError("Compile audit classes before packaging")
        staging = build / (owner + "-integrated-audit.jar")
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(staging, "x", compression=zipfile.ZIP_DEFLATED) as target:
            for entry in source.infolist():
                payload = source.read(entry.filename)
                if entry.filename.endswith(".class"):
                    if payload != (build / "classes/java" / production_set / entry.filename).read_bytes():
                        raise ValueError("Compiled production class differs from accepted release: " + entry.filename)
                target.writestr(entry, payload)
            for path in classes:
                name = path.relative_to(output).as_posix()
                if not name.startswith(prefix) or name in source.namelist():
                    raise ValueError("Unexpected audit class owner or duplicate: " + name)
                target.write(path, name)
        matches = [path for path in (fixture / "mods").glob("*.jar") if owner in mod_ids(path)]
        if len(matches) != 1:
            raise ValueError("Expected exactly one packaged owner: " + owner)
        # Replace the directory entry rather than overwriting a possibly linked input.
        temporary = matches[0].with_suffix(".integrated-package.tmp")
        with staging.open("rb") as source, temporary.open("xb") as destination:
            shutil.copyfileobj(source, destination)
        os.replace(temporary, matches[0])
        probes.extend(evidence(path) for path in classes)
    write(fixture / "integrated-audit-candidate.json", dict(createdAt=datetime.now().astimezone().isoformat(), auditClasses=probes,
          mods=[dict(**evidence(path), modIds=mod_ids(path)) for path in sorted((fixture / "mods").glob("*.jar"))]))
    print("Packaged", len(probes), "opt-in audit classes; every original release entry retained")


def exclusive(path):
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateFileW.restype = ctypes.c_void_p
    kernel.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p]
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    handle = kernel.CreateFileW(str(path), 0x80000000, 0, None, 3, 0x80, None)
    if handle == ctypes.c_void_p(-1).value:
        raise OSError(ctypes.get_last_error(), "Fixture file is not exclusively stopped: " + str(path))
    kernel.CloseHandle(handle)


def sqlite_snapshot(path):
    exclusive(path)
    if any(Path(str(path) + suffix).exists() for suffix in ["-wal", "-shm", "-journal"]):
        raise ValueError("Uncheckpointed SQLite sidecar: " + str(path))
    tables = {}
    with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as database:
        if database.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
            raise ValueError("Fixture SQLite integrity failure")
        for name, definition in database.execute("SELECT name,sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"):
            if not re.fullmatch(r"[A-Za-z0-9_]+", name):
                raise ValueError("Unexpected fixture table identifier")
            rows = [[dict(blob=value.hex()) if isinstance(value, bytes) else value for value in row] for row in database.execute('SELECT * FROM "' + name + '"')]
            tables[name] = dict(sql=definition, rows=sorted(rows, key=lambda row: json.dumps(row, ensure_ascii=False, sort_keys=True)))
        if "ledger_entry" in tables:
            if database.execute("SELECT transaction_id,currency_id,SUM(delta_minor_units) FROM ledger_entry GROUP BY transaction_id,currency_id HAVING SUM(delta_minor_units)<>0").fetchall():
                raise ValueError("Unbalanced integrated Economy ledger")
        if "blocks" in tables and database.execute("PRAGMA foreign_key_check(blocks)").fetchall():
            raise ValueError("Unresolved integrated block/entity references")
    return dict(database=evidence(path), quickCheck="ok", tables=tables)


def seed(fixture, parent, phase):
    """Continue from an immutable accepted image without replaying its gameplay."""
    marker = read(fixture / "integrated-audit-fixture.json")
    original = read(parent / "integrated-audit-fixture.json")
    result_path = parent / "runs" / phase / "result.json"
    result = read(result_path)
    if result["exitCode"] != 0 or not result["stopped"]:
        raise ValueError("Seed requires a completed stopped acceptance image")
    if (fixture / "runs").exists() or (fixture / marker["world"]).exists() or (fixture / "integrated-audit-seed.json").exists():
        raise ValueError("Seed requires a fresh prepared fixture")
    if [(Path(r["path"]).name, r["sha256"]) for r in marker["mods"]] != [(Path(r["path"]).name, r["sha256"]) for r in original["mods"]]:
        raise ValueError("Seed release inventories differ")
    for record in result["nativeFiles"]:
        checked(record)
    for record in marker["inputs"]:
        checked(record)
    image = parent / "runs" / phase
    with sqlite3.connect((image / "world/yuuniverse_economy/economy.db").as_uri()+"?mode=ro&immutable=1", uri=True) as db:
        if db.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
            raise ValueError("Seed database is invalid")
    if evidence(image / "database.db")["sha256"] != read(image / "grieflogger-snapshot.json")["database"]["sha256"]:
        raise ValueError("Seed logging database differs from accepted snapshot")
    # Config is generated and pinned in this fresh fixture. Overlay only accepted files.
    if any(not (image / "config" / path.relative_to(fixture / "config")).is_file() for path in (fixture / "config").rglob("*") if path.is_file()):
        raise ValueError("Seed config would retain unrelated generated files")
    shutil.copytree(image / "world", fixture / marker["world"])
    shutil.copytree(image / "config", fixture / "config", dirs_exist_ok=True)
    shutil.copyfile(image / "database.db", fixture / "database.db")
    write(fixture / "integrated-audit-seed.json", dict(createdAt=datetime.now().astimezone().isoformat(),
          parentFixture=parent.relative_to(ROOT).as_posix(), afterPhase=phase, result=evidence(result_path),
          liveFiles=[evidence(path) for folder in [fixture / marker["world"], fixture / "config"] for path in sorted(folder.rglob("*")) if path.is_file()] + [evidence(fixture / "database.db")]))
    print("Seeded fresh continuation from accepted", phase, "image; original attempts retained")


def run(fixture, java_home, phase):
    marker = read(fixture / "integrated-audit-fixture.json")
    candidate = read(fixture / "integrated-audit-candidate.json")
    if marker["marker"] != "citizens-integrated-audit" or marker["phases"] != PHASES:
        raise ValueError("Unexpected integrated fixture marker")
    output = fixture / "runs" / phase
    if output.exists():
        raise ValueError("Preserve existing integrated phase attempt: " + phase)
    inherited = read(fixture / "integrated-audit-seed.json") if (fixture / "integrated-audit-seed.json").exists() else None
    inherited_index = PHASES.index(inherited["afterPhase"]) if inherited else -1
    if PHASES.index(phase) <= inherited_index:
        raise ValueError("Do not replay inherited completed acceptance")
    for record in candidate["mods"]:
        checked(record)
    if len(list((fixture / "mods").glob("*.jar"))) != len(candidate["mods"]):
        raise ValueError("Integrated mod inventory changed")
    for previous in PHASES[:PHASES.index(phase)]:
        source = ROOT / inherited["parentFixture"] if PHASES.index(previous) <= inherited_index else fixture
        if read(source / "runs" / previous / "result.json")["exitCode"] != 0:
            raise ValueError("Complete previous phase first: " + previous)
    if PHASES.index(phase):
        prior = inherited if PHASES.index(phase) == inherited_index + 1 else read(fixture / "runs" / PHASES[PHASES.index(phase) - 1] / "result.json")
        for record in prior["liveFiles"]:
            checked(record)
    properties = {}
    for line in (fixture / "server.properties").read_text(encoding="utf-8-sig").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            if key in properties:
                raise ValueError("Duplicate fixture property: " + key)
            properties[key] = value
    for key, value in {"server-ip": "127.0.0.1", "server-port": str(marker["port"]), "level-name": marker["world"], "enable-rcon": "false", "enable-query": "false"}.items():
        if properties.get(key) != value:
            raise ValueError("Unexpected integrated fixture property: " + key)
    with socket.socket() as probe:
        probe.settimeout(1)
        if probe.connect_ex(("127.0.0.1", marker["port"])) == 0:
            raise ValueError("Integrated fixture port is already occupied")
    output.mkdir(parents=True, exist_ok=False)
    write(output / "attempt.json", dict(createdAt=datetime.now().astimezone().isoformat(), phase=phase, candidate=evidence(fixture / "integrated-audit-candidate.json")))
    environment = os.environ.copy()
    for key in ["CITIZENS_REWARD_AUDIT_PHASE", "CITIZENS_COMMERCE_AUDIT_PHASE", "CITIZENS_TEAM_MUTATION_AUDIT"]:
        environment.pop(key, None)
    environment["CITIZENS_INTEGRATED_AUDIT_PHASE"] = phase
    environment["CITIZENS_LOGGING_AUDIT_PHASE"] = "recover" if phase == "logging-recover" else "native"
    with (output / "console.log").open("xb") as log:
        process = subprocess.Popen([str(Path(java_home) / "bin/java.exe"), "-Xmx4G", "@launch-args.txt", "--nogui"], cwd=fixture, env=environment, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT)
        write(output / "process.json", dict(pid=process.pid, startedAt=datetime.now().astimezone().isoformat(), phase=phase))
        code = process.wait()
        write(output / "exit.json", dict(pid=process.pid, exitCode=code, terminalAt=datetime.now().astimezone().isoformat()))
        process.stdin.close()
    shutil.copyfile(fixture / "logs/latest.log", output / "server.log")
    text = (output / "console.log").read_text(encoding="utf-8", errors="replace")
    expected = {"xmod": "[XMODAUDIT] COMPLETE", "quest": "[QUESTAUDIT] COMPLETE", "logging": "[LOGGINGAUDIT] COMPLETE 37 checks", "logging-recover": "[LOGGINGAUDIT] COMPLETE 8 restart checks"}[phase]
    if code or expected not in text or any(tag in text for tag in ["[XMODAUDIT] FAILED", "[QUESTAUDIT] FAILED", "[LOGGINGAUDIT] FAILED", "[INTEGRATEDAUDIT] FAILED"]):
        raise RuntimeError("Integrated audit failed; preserve process/log/fixture: " + phase)
    world = fixture / marker["world"]
    exclusive(world / "session.lock")
    exclusive(world / "neoprotect/neoprotect.mv.db")
    databases = sorted(path for path in fixture.rglob("*.db") if not path.is_relative_to(fixture / "runs") and path.read_bytes()[:16] == b"SQLite format 3\x00")
    known = {fixture / "database.db": "grieflogger", world / "yuuniverse_economy/economy.db": "economy"}
    snapshots = {known.get(path, "sqlite-" + str(index)): sqlite_snapshot(path) for index, path in enumerate(databases)}
    if not {"grieflogger", "economy"}.issubset(snapshots):
        raise ValueError("Required logging/financial snapshots are absent")
    for name, value in snapshots.items():
        write(output / (name + "-snapshot.json"), value)
    shutil.copytree(world, output / "world")
    shutil.copytree(fixture / "config", output / "config")
    shutil.copyfile(fixture / "database.db", output / "database.db")
    write(output / "result.json", dict(phase=phase, completedAt=datetime.now().astimezone().isoformat(), exitCode=code,
          markers=re.findall(r"\[[A-Z]+AUDIT\] COMPLETE[^\r\n]*", text), console=evidence(output / "console.log"),
          serverLog=evidence(output / "server.log"), snapshots={name: evidence(output / (name + "-snapshot.json")) for name in snapshots},
          nativeFiles=[evidence(path) for folder in [output / "world", output / "config"] for path in sorted(folder.rglob("*")) if path.is_file()],
          liveFiles=[evidence(path) for folder in [world, fixture / "config"] for path in sorted(folder.rglob("*")) if path.is_file()] + [evidence(fixture / "database.db")],
          stopped=True))
    print("PASS packaged integrated phase", phase, "with stopped world, exact SQL snapshots and balanced ledger")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["prepare", "package", "seed", "run"])
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--build")
    parser.add_argument("--java-home")
    parser.add_argument("--phase", choices=PHASES)
    parser.add_argument("--parent")
    parser.add_argument("--link-providers", action="store_true")
    args = parser.parse_args()
    if args.mode == "package" and not args.build:
        parser.error("package requires --build")
    if args.mode == "run" and (not args.java_home or not args.phase):
        parser.error("run requires --java-home and --phase")
    if args.mode == "seed" and (not args.parent or not args.phase):
        parser.error("seed requires --parent and --phase")
    selected = fixture_path(args.fixture)
    if args.mode == "prepare":
        prepare(selected, args.link_providers)
    elif args.mode == "package":
        package(selected, fixture_path(args.build))
    elif args.mode == "seed":
        seed(selected, fixture_path(args.parent), args.phase)
    else:
        run(selected, args.java_home, args.phase)
