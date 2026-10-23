"""Rehearse the private Paradigm reader repair in fresh copies of the accepted pack."""
from datetime import datetime
from pathlib import Path
import argparse
import copy
import hashlib
import importlib.util
import os
import shutil
import socket
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("paradigm_capture", ROOT / "tools/integrated-runtime-audit.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
read, write, evidence, checked = base.read, base.write, base.evidence, base.checked


def prepare(fixture, variant):
    accepted = read(ROOT / "docs/integrated-provider-validation-2026-10-23.json")
    parent = ROOT / accepted["fixture"]
    result_path = parent / "runs/logging-recover/result.json"
    result = read(result_path)
    assert result["exitCode"] == 0 and result["stopped"]
    for record in result["nativeFiles"]:
        checked(record)
    for record in accepted["finalRuntimeInputs"]:
        checked(record)
    candidate = read(parent / "integrated-audit-candidate.json")
    for record in candidate["mods"]:
        checked(record)
    replacement = read(ROOT / "artifacts/paradigm-hologram-private-final-build/candidate.json")["candidate"]
    checked(replacement)
    fixture.mkdir(exist_ok=False)
    (fixture / "mods").mkdir()
    for record in candidate["mods"]:
        source = ROOT / record["path"]
        if variant == "fixed" and "paradigm" in record["modIds"]:
            source = ROOT / replacement["path"]
        os.link(source, fixture / "mods" / Path(record["path"]).name)
    image = parent / "runs/logging-recover"
    shutil.copytree(image / "world", fixture / "integrated-world")
    shutil.copytree(image / "config", fixture / "config")
    shutil.copyfile(image / "database.db", fixture / "database.db")
    shutil.copytree(parent / "kubejs/server_scripts", fixture / "kubejs/server_scripts")
    for name in ["server.properties", "launch-args.txt", "eula.txt"]:
        shutil.copyfile(parent / name, fixture / name)
    properties = (fixture / "server.properties").read_text(encoding="utf-8-sig")
    assert properties.count("server-port=25641") == 1
    (fixture / "server.properties").write_text(properties.replace("server-port=25641", "server-port=25642"), encoding="utf-8")
    write(fixture / "paradigm-hologram-fixture.json", dict(createdAt=datetime.now().astimezone().isoformat(), variant=variant,
          acceptedResult=evidence(result_path), privateCandidate=replacement, parent=parent.relative_to(ROOT).as_posix(),
          mods=[dict(**evidence(path), modIds=base.mod_ids(path)) for path in sorted((fixture / "mods").glob("*.jar"))],
          initialFiles=[evidence(path) for folder in [fixture / "integrated-world", fixture / "config", fixture / "kubejs"] for path in sorted(folder.rglob("*")) if path.is_file()] + [evidence(fixture / name) for name in ["database.db", "server.properties", "launch-args.txt", "eula.txt"]]))
    print("Prepared fresh", variant, "native pack with immutable providers and accepted stopped data")


def package(fixture, build):
    marker = read(fixture / "paradigm-hologram-fixture.json")
    if (fixture / "paradigm-hologram-candidate.json").exists() or (fixture / "runs").exists():
        raise ValueError("Preserve existing packaged attempts")
    for record in marker["mods"]:
        checked(record)
    classes = sorted((build / "classes/java/paradigmHologramProbe").rglob("*.class"))
    assert len(classes) == 1
    name = classes[0].relative_to(build / "classes/java/paradigmHologramProbe").as_posix()
    assert name == "net/citizensnpcs/audit/ParadigmHologramRuntimeAudit.class"
    target = checked(next(record for record in marker["mods"] if "citizens" in record["modIds"]))
    temporary = fixture / "citizens-probe.jar.tmp"
    with zipfile.ZipFile(target) as source, zipfile.ZipFile(temporary, "x") as output:
        assert name not in source.namelist()
        for entry in source.infolist():
            output.writestr(copy.copy(entry), source.read(entry.filename))
        output.write(classes[0], name)
    with zipfile.ZipFile(target) as source, zipfile.ZipFile(temporary) as output:
        assert all(source.read(path) == output.read(path) for path in source.namelist())
    os.replace(temporary, target)
    write(fixture / "paradigm-hologram-candidate.json", dict(probe=evidence(classes[0]),
          mods=[dict(**evidence(path), modIds=base.mod_ids(path)) for path in sorted((fixture / "mods").glob("*.jar"))]))
    print("Packaged one isolated native probe without overwriting any linked input")


def run(fixture, phase, java_home):
    marker = read(fixture / "paradigm-hologram-fixture.json")
    output = fixture / "runs" / ("paradigm-"+phase)
    if output.exists():
        raise ValueError("Preserve existing native phase attempt")
    if phase == "recover":
        prior = read(fixture / "runs/paradigm-seed/result.json")
        assert marker["variant"] == "fixed" and prior["accepted"]
        inputs = prior["liveFiles"]
    else:
        inputs = marker["initialFiles"]
    for record in inputs:
        checked(record)
    for record in marker["initialFiles"]:
        if Path(record["path"]).name in ["server.properties", "launch-args.txt", "eula.txt"]:
            if phase == "recover" and Path(record["path"]).name == "server.properties":
                # Vanilla Properties.store rewrites its date comment on startup.
                source = ROOT / marker["parent"] / "server.properties"
                expected = source.read_text(encoding="utf-8-sig").replace("server-port=25641", "server-port=25642")
                payload = expected.replace("\n", os.linesep).encode("utf-8")
                assert len(payload) == record["bytes"] and hashlib.sha256(payload).hexdigest() == record["sha256"]
                settings = lambda text: [line for line in text.splitlines() if not line.startswith(("#", "!"))]
                assert settings(expected) == settings((fixture / "server.properties").read_text(encoding="utf-8-sig")), "Server settings changed after seed"
            else:
                checked(record)
    for record in read(fixture / "paradigm-hologram-candidate.json")["mods"]:
        checked(record)
    properties = (fixture / "server.properties").read_text(encoding="utf-8-sig")
    for line in ["server-ip=127.0.0.1", "server-port=25642", "level-name=integrated-world", "enable-rcon=false", "enable-query=false"]:
        assert properties.splitlines().count(line) == 1
    with socket.socket() as probe:
        assert probe.connect_ex(("127.0.0.1", 25642)) != 0
    output.mkdir(parents=True)
    write(output / "attempt.json", dict(createdAt=datetime.now().astimezone().isoformat(), variant=marker["variant"], phase=phase,
          candidate=evidence(fixture / "paradigm-hologram-candidate.json"),
          launchInputs=[evidence(fixture / name) for name in ["server.properties", "launch-args.txt", "eula.txt"]]))
    environment = os.environ.copy()
    for key in ["CITIZENS_REWARD_AUDIT_PHASE", "CITIZENS_COMMERCE_AUDIT_PHASE", "CITIZENS_TEAM_MUTATION_AUDIT", "CITIZENS_LOGGING_AUDIT_PHASE"]:
        environment.pop(key, None)
    environment["CITIZENS_INTEGRATED_AUDIT_PHASE"] = "paradigm-"+phase
    environment["PARADIGM_HOLOGRAM_AUDIT_PHASE"] = phase
    with (output / "console.log").open("xb") as log:
        process = subprocess.Popen([str(Path(java_home) / "bin/java.exe"), "-Xmx4G", "@launch-args.txt", "--nogui"], cwd=fixture,
                                   env=environment, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT)
        write(output / "process.json", dict(pid=process.pid, startedAt=datetime.now().astimezone().isoformat()))
        code = process.wait()
        write(output / "exit.json", dict(pid=process.pid, exitCode=code, terminalAt=datetime.now().astimezone().isoformat()))
        process.stdin.close()
    text = (output / "console.log").read_text(encoding="utf-8", errors="replace")
    accepted = marker["variant"] == "fixed"
    assert code == 0
    if accepted:
        assert "[PARADIGMHOLOAUDIT] COMPLETE" in text and "[PARADIGMHOLOAUDIT] FAILED" not in text
        assert "keeping holograms disabled for safety" not in text and "[INTEGRATEDAUDIT] FAILED" not in text
    else:
        assert "[PARADIGMHOLOAUDIT] FAILED" in text and "AssertionError: provider_holograms_enabled_on_existing_file" in text
        assert "AccessDeniedException" in text and "keeping holograms disabled for safety" in text
    shutil.copyfile(fixture / "logs/latest.log", output / "server.log")
    world = fixture / "integrated-world"
    base.exclusive(world / "session.lock")
    base.exclusive(world / "neoprotect/neoprotect.mv.db")
    snapshots = {"economy": base.sqlite_snapshot(world / "yuuniverse_economy/economy.db"),
                 "grieflogger": base.sqlite_snapshot(fixture / "database.db"),
                 "paradigm": base.sqlite_snapshot(fixture / "config/paradigm/data/paradigm.db")}
    for name, snapshot in snapshots.items():
        write(output / (name+"-snapshot.json"), snapshot)
    if accepted:
        shutil.copytree(world, output / "world")
        shutil.copytree(fixture / "config", output / "config")
        shutil.copyfile(fixture / "database.db", output / "database.db")
        state = fixture / ("hologram-audit-seed.json" if phase == "seed" else "hologram-audit-recovered.json")
        shutil.copyfile(state, output / "state.json")
    states = [evidence(path) for name in ["hologram-audit-seed.json", "hologram-audit-recovered.json"] if (path := fixture / name).is_file()]
    write(output / "result.json", dict(accepted=accepted, phase=phase, exitCode=code, completedAt=datetime.now().astimezone().isoformat(),
          console=evidence(output / "console.log"), snapshots=[evidence(output / (name+"-snapshot.json")) for name in snapshots],
          liveFiles=[evidence(path) for folder in [world, fixture / "config", fixture / "kubejs"] for path in sorted(folder.rglob("*")) if path.is_file()] + [evidence(fixture / "database.db"), *states],
          capturedFiles=[evidence(path) for path in sorted(output.rglob("*")) if path.is_file()],
          runtimeInputs=[evidence(fixture / name) for name in ["server.properties", "launch-args.txt", "eula.txt"]], stopped=True))
    print("PASS native", marker["variant"], phase, "acceptance" if accepted else "original defect reproduction", "with actual zero exit and stopped storage")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["prepare", "package", "run"])
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--variant", choices=["before", "fixed"])
    parser.add_argument("--build")
    parser.add_argument("--phase", choices=["seed", "recover"])
    parser.add_argument("--java-home")
    args = parser.parse_args()
    fixture = base.fixture_path(args.fixture)
    if args.mode == "prepare":
        if not args.variant: parser.error("prepare requires --variant")
        prepare(fixture, args.variant)
    elif args.mode == "package":
        if not args.build: parser.error("package requires --build")
        package(fixture, base.fixture_path(args.build))
    else:
        if not args.phase or not args.java_home: parser.error("run requires --phase and --java-home")
        run(fixture, args.phase, args.java_home)
