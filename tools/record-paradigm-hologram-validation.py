"""Verify the private owner repair against retained packaged, native and saved evidence."""
from datetime import datetime
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import re
import shutil
import socket
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


prior = module("hologram_prior", "record-integrated-provider-validation.py")
native = module("hologram_nbt", "record-guard-restart-validation.py")
builder = module("hologram_builder", "build-paradigm-hologram-candidate.py")
read, evidence, checked = prior.read, prior.evidence, prior.checked


def verify_build():
    directory = ROOT / "artifacts/paradigm-hologram-private-final-build"
    result = read(directory / "candidate.json")
    for record in [result[key] for key in ["baseline", "fixedSource", "provider", "candidate"]] + result["dependencies"] + result["buildFiles"]:
        path = Path(record["path"])
        path = path if path.is_absolute() else ROOT / path
        assert hashlib.sha256(path.read_bytes()).hexdigest() == record["sha256"]
        assert path.stat().st_size == record["bytes"]
    owner = builder.OWNER + ".class"
    with zipfile.ZipFile(checked(result["provider"])) as before, zipfile.ZipFile(checked(result["candidate"])) as after:
        assert before.namelist() == after.namelist()
        assert len(after.namelist()) == len(set(after.namelist()))
        assert [name for name in before.namelist() if before.read(name) != after.read(name)] == [owner]
        assert before.read(owner)[6:8] == after.read(owner)[6:8] == (61).to_bytes(2, "big")
    installed = (directory / "installed-bytecode.txt").read_text(encoding="utf-8")
    assert installed == (directory / "baseline-bytecode.txt").read_text(encoding="utf-8")
    a, b = builder.methods(installed), builder.methods((directory / "fixed-bytecode.txt").read_text(encoding="utf-8"))
    assert a.keys() == b.keys()
    assert [key for key in a if a[key] != b[key]] == result["changedMethods"]
    assert len(result["changedMethods"]) == 1 and " load();" in result["changedMethods"][0]
    assert len({(directory / (name + "-descriptors.txt")).read_bytes() for name in ["installed", "baseline", "fixed"]}) == 1
    return evidence(directory / "candidate.json")


def package(fixture, build):
    marker = read(fixture / "paradigm-hologram-fixture.json")
    candidate = read(fixture / "paradigm-hologram-candidate.json")
    assert len(candidate["mods"]) == len(marker["mods"]) == 64
    original = read(ROOT / marker["parent"] / "integrated-audit-candidate.json")
    originals = {Path(record["path"]).name: record for record in original["mods"]}
    parent = ROOT / marker["parent"]
    for record in marker["initialFiles"]:
        relative = (ROOT / record["path"]).relative_to(fixture)
        if relative.parts[0] == "integrated-world":
            source = parent / "runs/logging-recover/world" / relative.relative_to("integrated-world")
        elif relative.parts[0] == "config" or relative.name == "database.db":
            source = parent / "runs/logging-recover" / relative
        else:
            source = parent / relative
        if relative.name == "server.properties":
            payload = source.read_text(encoding="utf-8-sig").replace("server-port=25641", "server-port=25642").replace("\n", os.linesep).encode("utf-8")
            assert len(payload) == record["bytes"] and hashlib.sha256(payload).hexdigest() == record["sha256"]
        else:
            assert evidence(source)["sha256"] == record["sha256"] and source.stat().st_size == record["bytes"]
    for record in candidate["mods"]:
        target = checked(record)
        release = checked(originals[target.name])
        if "citizens" in record["modIds"]:
            probe = checked(candidate["probe"])
            name = "net/citizensnpcs/audit/ParadigmHologramRuntimeAudit.class"
            with zipfile.ZipFile(release) as a, zipfile.ZipFile(target) as b:
                assert b.namelist() == a.namelist() + [name]
                assert all(a.read(key) == b.read(key) for key in a.namelist())
                assert b.read(name) == probe.read_bytes()
        else:
            expected = read(checked(build))["candidate"] if "paradigm" in record["modIds"] and marker["variant"] == "fixed" else originals[target.name]
            assert record["sha256"] == expected["sha256"]
    return candidate


def snapshot(directory, fixture, name, accepted):
    record = read(directory / (name + "-snapshot.json"))
    relative = (ROOT / record["database"]["path"]).relative_to(fixture)
    path = directory / "world" / relative.relative_to("integrated-world") if accepted and relative.parts[0] == "integrated-world" else directory / relative if accepted else fixture / relative
    assert evidence(path)["sha256"] == record["database"]["sha256"]
    assert prior.sql_snapshot(path) == record["tables"]
    return record["tables"]


def launch_guards(fixtures):
    protected = [path for fixture in fixtures for path in sorted(fixture.rglob("*")) if path.is_file()]
    before = [evidence(path) for path in protected]
    attempts = []
    for fixture, phases in zip(fixtures, [["seed"], ["seed", "recover"]], strict=True):
        for phase in phases:
            result = subprocess.run(["python", "-B", str(ROOT / "tools/paradigm-hologram-runtime-audit.py"), "run", "--fixture", str(fixture),
                                     "--phase", phase, "--java-home", str(ROOT / "artifacts/nonexistent-hologram-java")], cwd=ROOT, capture_output=True)
            message = (result.stdout + result.stderr).decode("utf-8", errors="replace")
            assert result.returncode != 0 and "Preserve existing native phase attempt" in message, message
            attempts.append(dict(fixture=fixture.relative_to(ROOT).as_posix(), phase=phase, exitCode=result.returncode, output=message))
    assert before == [evidence(path) for path in protected]
    return dict(protectedFiles=len(before), attempts=attempts)


def validate(output):
    build = verify_build()
    accepted_prior = read(ROOT / "docs/integrated-provider-validation-2026-10-23.json")
    parent = ROOT / accepted_prior["fixture"]
    parent_world = parent / "runs/logging-recover/world"
    parent_sql = {name: read(parent / "runs/logging-recover" / (name + "-snapshot.json"))["tables"] for name in ["economy", "grieflogger"]}
    fixtures = [ROOT / ("artifacts/paradigm-hologram-native-" + variant + "-server") for variant in ["before", "fixed"]]
    candidates = [package(fixture, build) for fixture in fixtures]
    assert candidates[0]["probe"]["sha256"] == candidates[1]["probe"]["sha256"]
    phases, inventories, states, original_files = [], [], [], []
    for fixture, candidate, phase in [(fixtures[0], candidates[0], "seed"), (fixtures[1], candidates[1], "seed"), (fixtures[1], candidates[1], "recover")]:
        directory = fixture / "runs" / ("paradigm-" + phase)
        result = read(directory / "result.json")
        accepted = fixture == fixtures[1]
        assert result["accepted"] == accepted and result["phase"] == phase and result["stopped"] and result["exitCode"] == 0
        process, terminal = read(directory / "process.json"), read(directory / "exit.json")
        assert process["pid"] == terminal["pid"] and terminal["exitCode"] == 0
        for record in result["capturedFiles"]:
            checked(record)
        log = checked(result["console"]).read_text(encoding="utf-8", errors="replace")
        passes = re.findall(r"\[PARADIGMHOLOAUDIT\] PASS (.+)", log)
        if accepted:
            counts = re.findall(r"\[PARADIGMHOLOAUDIT\] COMPLETE (\d+) checks", log)
            assert counts == [str(len(passes))] and "[PARADIGMHOLOAUDIT] FAILED" not in log
            state = read(directory / "state.json")
            assert state["checks"] == len(passes) and state["phase"] == phase
            config_path = directory / "config/paradigm/holograms.json"
            assert read(config_path) == state["config"]
            assert set(state["entities"]) == set(state["texts"]) == {"paradigm_hologram:line:reader_lifetime:0", "paradigm_hologram:line:reader_lifetime:1"}
            assert len(set(state["entities"].values())) == 2
            assert list(state["texts"].values()) == ["Owner reader lifetime", "Literal $5 \\ Unicode 雪"]
            definition = state["config"]["holograms"]["reader_lifetime"]
            assert definition["lines"] == list(state["texts"].values())
            assert all(definition[key] == value for key, value in dict(dimension="minecraft:overworld", x=3.125, y=-59.5, z=3.875, viewDistance=37.5, refreshIntervalSeconds=1, lineSpacing=0.42).items())
            assert state["config"]["enabled"] and state["config"]["defaultViewDistance"] == 51 and state["config"]["defaultRefreshIntervalSeconds"] == 1
            states.append((state, config_path.read_bytes()))
        else:
            assert "[PARADIGMHOLOAUDIT] FAILED" in log and "AssertionError: provider_holograms_enabled_on_existing_file" in log
            assert "AccessDeniedException" in log and "keeping holograms disabled for safety" in log
        inventories.append(prior.runtime_inventory(directory, candidate))
        saved = {name: snapshot(directory, fixture, name, accepted) for name in ["economy", "grieflogger", "paradigm"]}
        for name in ["economy", "grieflogger"]:
            for table, old in parent_sql[name].items():
                new = saved[name][table]
                assert old["sql"] == new["sql"]
                if name == "economy" and table in ["currency", "account"]:
                    assert [row[:-1] for row in old["rows"]] == [row[:-1] for row in new["rows"]]
                    assert all(a[-1] <= b[-1] for a, b in zip(old["rows"], new["rows"], strict=True))
                    if table == "account":
                        assert [row for row in old["rows"] if row[1] == "PLAYER"] == [row for row in new["rows"] if row[1] == "PLAYER"]
                elif name == "economy" and table == "audit_event":
                    assert all(row in new["rows"] for row in old["rows"])
                    added = [row for row in new["rows"] if row not in old["rows"]]
                    assert len(added) == (2 if phase == "recover" else 1)
                    assert all(row[1:5] == [None, "CURRENCY_SET_SYNCHRONIZED", None, "active_count=" + str(len(parent_sql["economy"]["currency"]["rows"]))] for row in added)
                else:
                    assert old == new, (fixture.name, phase, name, table)
        world = directory / "world" if accepted else fixture / "integrated-world"
        prior.exclusive(world / "session.lock")
        prior.exclusive(world / "neoprotect/neoprotect.mv.db")
        original = sorted((parent_world / "citizens").rglob("*.json")) + sorted(parent_world.glob("ftbquests/*.snbt")) + sorted(parent_world.glob("playerdata/*.dat"))
        for path in original:
            target = world / path.relative_to(parent_world)
            assert path.read_bytes() == target.read_bytes(), target
        original_files.append(len(original))
        entities = native.entity_regions(world)
        displays = [entity for entity in entities if any(tag.startswith("paradigm_hologram:line:reader_lifetime:") for tag in entity.get("Tags", []))]
        assert not displays, "Provider left owned displays in the stopped entity regions"
        phases.append(dict(variant="fixed" if accepted else "before", phase=phase, accepted=accepted, checks=len(passes),
                           result=evidence(directory / "result.json"), process=evidence(directory / "process.json"), terminal=evidence(directory / "exit.json"),
                           loadedProviders=evidence(directory / "loaded-providers.json"), stoppedOwnedDisplayCount=len(displays)))
    assert all(value == inventories[0] for value in inventories)
    assert states[0][1] == states[1][1], "Native restart changed configuration bytes"
    # Validate current final live files separately: accepted seed images remain authoritative for the earlier process.
    for fixture, phase in zip(fixtures, ["seed", "recover"], strict=True):
        final_result = read(fixture / "runs" / ("paradigm-" + phase) / "result.json")
        for record in final_result["liveFiles"] + final_result.get("runtimeInputs", []):
            checked(record)
    protected_prior = []
    for record in accepted_prior["originalProtectedFiles"] + accepted_prior["excludedFailedAttempt"] + accepted_prior["finalRuntimeInputs"]:
        checked(record)
        protected_prior.append(record)
    for record in read(parent / "runs/logging-recover/result.json")["liveFiles"]:
        checked(record)
        protected_prior.append(record)
    for record in accepted_prior["phases"]:
        result = read(checked(record["result"]))
        for item in result["nativeFiles"]:
            checked(item)
            protected_prior.append(item)
    standalone = []
    for variant in ["before", "fixed"]:
        path = ROOT / ("artifacts/paradigm-hologram-store-" + variant + ".log")
        text = path.read_text(encoding="utf-8-sig")
        passes = re.findall(r"\[HOLOGRAMSTOREAUDIT\] PASS (.+)", text)
        if variant == "fixed":
            assert len(passes) == 38 and "COMPLETE 38 checks" in text
        else:
            assert "AssertionError: existing_valid_file_stays_enabled" in text
        standalone.append(dict(variant=variant, accepted=variant == "fixed", checks=len(passes), log=evidence(path)))
    with socket.socket() as probe:
        assert probe.connect_ex(("127.0.0.1", 25642)) != 0
    guards = launch_guards(fixtures)
    preparations = [evidence(ROOT / path) for path in [
        "artifacts/paradigm-hologram-baseline-difference.txt", "artifacts/paradigm-hologram-baseline-bytecode.txt",
        "artifacts/paradigm-hologram-baseline-compile.log", "artifacts/paradigm-hologram-original-compile.log",
        "artifacts/paradigm-hologram-original-bytecode.txt", "artifacts/paradigm-hologram-before-zip-metadata.py",
        "artifacts/paradigm-hologram-private-build.log", "artifacts/paradigm-hologram-private-final-build.log",
        "artifacts/paradigm-hologram-native-before-prepare.log", "artifacts/paradigm-hologram-native-fixed-prepare.log",
        "artifacts/paradigm-hologram-native-compile.log", "artifacts/paradigm-hologram-store-audit-compile.log",
        "artifacts/paradigm-hologram-verifier-before-windows-newlines.py", "artifacts/paradigm-hologram-verifier-before-windows-newlines.log",
        "artifacts/paradigm-hologram-independent-before-partial.log", "artifacts/paradigm-hologram-runtime-before-property-comment.py",
        "artifacts/paradigm-hologram-recover-before-property-comment.log", "artifacts/paradigm-hologram-cache-copy.json",
        "artifacts/paradigm-hologram-cache-cleanup-rejection.json"]]
    preparations += [evidence(path) for path in sorted((ROOT / "artifacts/paradigm-hologram-private-build").rglob("*")) if path.is_file()]
    references = [Path(__file__), ROOT / "tools/build-paradigm-hologram-candidate.py", ROOT / "tools/paradigm-file-audit/ParadigmHologramStoreAudit.java",
                  ROOT / "tools/paradigm-hologram-runtime-audit.py", ROOT / "tools/paradigm-hologram-runtime-audit.gradle"]
    references += sorted((ROOT / "tools/paradigm-hologram-runtime-audit").rglob("*.java"))
    references += sorted((ROOT / "artifacts/paradigm-hologram-store-audit-classes").rglob("*.class"))
    references += [checked(candidates[0]["probe"])]
    frozen = []
    for source in references:
        destination = ROOT / "artifacts/paradigm-hologram-executable-reference" / source.relative_to(ROOT)
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            assert source.read_bytes() == destination.read_bytes()
        else:
            shutil.copyfile(source, destination)
        frozen.append(dict(source=evidence(source), reference=evidence(destination)))
    report = dict(verifiedAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", privateLocalCandidate=True,
                  build=build, standalone=standalone, phases=phases, loadedProviderCount=len(inventories[0]),
                  packages=[dict(fixture=evidence(fixture / "paradigm-hologram-fixture.json"), candidate=evidence(fixture / "paradigm-hologram-candidate.json"), mods=candidate["mods"]) for fixture, candidate in zip(fixtures, candidates, strict=True)],
                  acceptedChecks=sum(row["checks"] for row in phases if row["accepted"]) + 38,
                  preservedOriginalFilesPerPhase=original_files, protectedPriorFiles=protected_prior, launchGuards=guards,
                  executableReferences=frozen, preparationReferences=preparations, priorValidation=evidence(ROOT / "docs/integrated-provider-validation-2026-10-23.json"),
                  limits=["Private owner adaptation only; no modified provider distribution or production activation", "Physical client rendering and VR acceptance remain deferred", "Supported Paradigm inbound API and other existing release gates remain open"])
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("PASS independent private hologram repair verification:", report["acceptedChecks"], "checks;", len(inventories[0]), "resolved providers")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    validate((ROOT / args.output).resolve())
