"""Validate retained native basin kill/restart records without modifying fixtures."""
from pathlib import Path
from datetime import datetime
import argparse, collections, hashlib, json, re, subprocess, zipfile

ROOT = Path(__file__).resolve().parent.parent


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8-sig"))


def evidence(path):
    path = (ROOT / path).resolve()
    raw = path.read_bytes()
    return dict(path=path.relative_to(ROOT).as_posix(), bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())


def files(path):
    return [evidence(p) for p in sorted((ROOT / path).rglob("*")) if p.is_file() and p.name != ".journal.lock"]


def amounts(values, key):
    result = collections.Counter()
    for value in values:
        if value:
            result[value["id"]] += value[key]
    return dict(result)


def stored(storage, processed):
    assert amounts(storage["items"], "count") == ({"minecraft:snowball": 3, "minecraft:flint": 1, "minecraft:paper": 2} if processed else {"minecraft:snowball": 5, "minecraft:flint": 2})
    assert amounts(storage["fluids"], "amount") == ({"minecraft:water": 500, "minecraft:lava": 100} if processed else {"minecraft:water": 750})
    if processed:
        paper = [v for v in storage["items"] if v.get("id") == "minecraft:paper"]
        assert paper == [dict(id="minecraft:paper", count=2, components={"minecraft:custom_name": '"Basin crash output"'})]


def validate(output, stopped_path, regression_log, regression_build):
    previous = read("docs/craft-binding-validation-2026-10-22.json")
    assert previous["productionSources"] == files("neoforge/src/main")
    assert subprocess.run(["git", "diff", "--quiet", "69cf58f", "--", "neoforge/src/main", "neoforge/src/dialogue", "neoforge/src/test", "tools/audit-tests", "tools/audit-tests.gradle", "neoforge/build.gradle"], cwd=ROOT).returncode == 0
    stopped = read(stopped_path)
    assert stopped["exclusiveStoppedWorldAndJournalOwnership"] and len(stopped["fixtures"]) == 7
    guards = read("artifacts/basin-crash-runner-guards.json")
    assert guards["noRejectedPathCreated"] and len(guards["cases"]) == 3 and all(case["rejected"] for case in guards["cases"])
    seen = {key: set() for key in ["run", "entry", "actor", "machine"]}
    cases = []
    for target in ["21.1.248", "21.1.233"]:
        for stage in ["prepared", "confirmed", "applied"]:
            prefix = f"artifacts/basin-crash-{target.replace('.', '-')}-{stage}"
            fixture = Path(f"artifacts/basin-crash-{target}-{stage}-server")
            assert fixture.as_posix() in stopped["fixtures"]
            state = read(prefix + "-state.json")
            termination = read(prefix + "-termination.json")
            recovered = read(fixture / "basin-crash-recovery.json")
            for key in state:
                assert state[key] == recovered[key], (prefix, key)
            for key in seen:
                assert state[key] not in seen[key]
                seen[key].add(state[key])
            assert state["stage"] == stage and state["entries"] == 1 and state["maximum"] == 1
            assert termination["stage"] == stage and termination["neoForge"] == target and termination["machine"] == "basin"
            assert termination["forcedTermination"] and termination["run"] == state["run"] and termination["entry"] == state["entry"]
            assert Path(termination["fixture"]).resolve() == (ROOT / fixture).resolve()
            build = (ROOT / f"artifacts/basin-crash-{target}-build").resolve()
            assert Path(termination["verifiedArgumentFile"]).resolve() == build / "moddev/serverRunProgramArgs.txt"
            assert Path(termination["projectCache"]).resolve() == (ROOT / f"artifacts/basin-crash-{target}-cache").resolve()
            assert datetime.fromisoformat(termination["processCreatedAt"]) <= datetime.fromisoformat(termination["killedAt"])
            killed = (ROOT / (prefix + "-killed.log")).read_text(encoding="utf-8-sig")
            killed_err = (ROOT / (prefix + "-killed-stderr.log")).read_text(encoding="utf-8-sig")
            marker = f"[MACHINECRASHAUDIT] KILL_READY pid={termination['pid']} entry={state['entry']} run={state['run']}"
            assert marker in killed and f"NeoForge {target}" in killed and "Stopping server" not in killed and "[CREATEAUDIT] FAILED" not in killed
            assert "BUILD FAILED" in killed + killed_err and "BUILD SUCCESSFUL" not in killed
            boundary_checks = len(re.findall(r"\[BASINCRASHAUDIT\] PASS ", killed))
            assert boundary_checks == {"prepared": 2, "confirmed": 3, "applied": 4}[stage]
            recovery_log = (ROOT / (prefix + "-recovered.log")).read_text(encoding="utf-8-sig")
            checks = {"prepared": 11, "confirmed": 11, "applied": 10}[stage]
            assert f"[BASINCRASHAUDIT] COMPLETE {checks} checks stage={stage}" in recovery_log
            assert "BUILD SUCCESSFUL" in recovery_log and f"NeoForge {target}" in recovery_log and "[CREATEAUDIT] FAILED" not in recovery_log
            stored(state["savedStorage"], False)
            stored(state["liveStorage"], stage != "prepared")
            stored(recovered["recoveredStorage"], True)
            assert recovered["recoveredProgress"] == (1 if stage == "confirmed" else 0)
            payload = state["evidence"]
            assert payload["recipeId"] == "citizens_audit:basin_crash" and payload["recipeRevision"] == state["revision"]
            assert payload["source"] == "CREATE_MIXER" and payload["attribution"]["machineId"] == state["machine"]
            assert payload["attribution"]["beneficiaryId"] == state["actor"]
            assert amounts(payload["basin"]["consumedItems"], "count") == {"minecraft:snowball": 2, "minecraft:flint": 1}
            assert amounts(payload["basin"]["consumedFluids"], "amount") == {"minecraft:water": 250}
            assert amounts(payload["basin"]["fluidOutputs"], "amount") == {"minecraft:lava": 100}
            assert payload["beforeOutput"] == state["savedStorage"]["items"] and payload["basin"]["beforeFluids"] == state["savedStorage"]["fluids"]
            assert payload["rolledOutputs"] == [v for v in recovered["recoveredStorage"]["items"] if v.get("id") == "minecraft:paper"]
            heads = recovered["recoveredEntries"]
            assert len(heads) == 1 and heads[0]["id"] == state["entry"] and heads[0]["revision"] == 2
            assert heads[0]["state"] == ("REJECTED" if stage == "prepared" else "APPLIED")
            assert heads[0]["admission"]["actorId"] == state["actor"] and heads[0]["admission"]["units"] == 2
            assert json.loads(heads[0]["admission"]["evidence"]) == payload
            journal = fixture / state["evidenceDirectory"] / "journal"
            records = [read(p)["payload"] for p in sorted((ROOT / journal).glob(f"entry-{state['entry']}-*.json"))]
            assert [e["state"] for e in records] == (["PREPARED", "REVIEW", "REJECTED"] if stage == "prepared" else ["PREPARED", "CONFIRMED", "APPLIED"])
            assert all(e["admission"] == records[0]["admission"] for e in records)
            assert records[-1] == {key: heads[0].get(key) for key in records[-1]}
            team = fixture / f"create-world/ftbquests/{state['team']}.snbt"
            team_text = (ROOT / team).read_text(encoding="utf-8-sig")
            progress = re.search(r"task_progress:\s*\{([^}]*)\}", team_text)
            persisted = {} if progress is None else {k: int(v) for k, v in re.findall(r"([0-9A-F]{16}):\s*(\d+)L", progress[1])}
            assert persisted.get(state["task"], 0) == recovered["recoveredProgress"]
            providers = files(fixture / "mods")
            assert {Path(p["path"]).name: p["sha256"] for p in providers} == {Path(p["path"]).name: p["sha256"] for p in previous["create"]["providers"]}
            cases.append(dict(target=target, stage=stage, boundaryChecks=boundary_checks, recoveryChecks=checks, state=state,
                logs=[evidence(prefix + suffix) for suffix in ["-killed.log", "-killed-stderr.log", "-recovered.log"]],
                termination=evidence(prefix + "-termination.json"), savedState=evidence(prefix + "-state.json"), recovery=evidence(fixture / "basin-crash-recovery.json"),
                journal=files(fixture / state["evidenceDirectory"]), team=evidence(team), providers=providers, scripts=files(fixture / "kubejs/server_scripts"),
                auditBinary=evidence(Path(f"artifacts/basin-crash-{target}-build/classes/java/dialogue/net/yuuniverse/interactions/BasinCrashRuntimeAudit.class"))))
    counts = dict(CREATEBATCHAUDIT=23, CREATEPRESSAUDIT=16, CREATETRANSPORTAUDIT=21, CREATEBASINAUDIT=23, CREATEFLUIDAUDIT=28, CREATEDEPLOYERAUDIT=26, CREATEFANAUDIT=31, CREATEASSEMBLYAUDIT=41, CREATEAUDIT=54)
    regression = (ROOT / regression_log).read_text(encoding="utf-8-sig")
    assert "BUILD SUCCESSFUL" in regression and "[CREATEAUDIT] FAILED" not in regression
    for name, count in counts.items():
        assert f"[{name}] COMPLETE {count} checks" in regression
    packages = []
    for package in previous["packages"]:
        assert evidence(package["path"]) == {k: package[k] for k in ["path", "bytes", "sha256"]}
        if package["path"].endswith("-sources.jar"):
            continue
        target = "21.1.248" if "21.1.248-build" in package["path"] else "21.1.233"
        dialogue = Path(package["path"]).name.startswith("yuuniverse-interactions")
        source_set = "dialogue" if dialogue else "main"
        with zipfile.ZipFile(ROOT / package["path"]) as archive:
            names = [n for n in archive.namelist() if n.endswith(".class")]
            for name in names:
                assert archive.read(name) == (ROOT / f"artifacts/basin-crash-{target}-build/classes/java/{source_set}" / name).read_bytes(), name
                if target == "21.1.248":
                    assert archive.read(name) == (ROOT / regression_build / f"classes/java/{source_set}" / name).read_bytes(), name
        packages.append(dict(**evidence(package["path"]), matchingProductionClasses=len(names)))
    for ordinary in previous["ordinary"]:
        assert ordinary["results"] == dict(tests=825, failures=0, errors=0, skipped=0)
        assert evidence(ordinary["log"]["path"]) == ordinary["log"]
        for report in ordinary["reports"]:
            assert evidence(report["path"]) == report
    assert previous["interactions"]["results"] == dict(tests=100, failures=0, errors=0, skipped=0)
    assert evidence(previous["interactions"]["log"]["path"]) == previous["interactions"]["log"]
    paths = ["tools/create-runtime-audit/net/yuuniverse/interactions/BasinCrashRuntimeAudit.java", "tools/create-runtime-audit/net/yuuniverse/interactions/CreateRuntimeAudit.java", "tools/prepare-create-audit.ps1", "tools/run-machine-crash-audit.ps1", "tools/create-runtime-audit.gradle", "tools/record-basin-crash-validation.py", "artifacts/run-basin-crash-matrix.ps1", "artifacts/run-basin-regression.ps1", "artifacts/check-basin-crash-stopped.ps1"]
    result = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", baseRevision="69cf58f", cases=cases,
        actualJvmKills=6, boundaryChecks=sum(c["boundaryChecks"] for c in cases), recoveryChecks=sum(c["recoveryChecks"] for c in cases),
        regression=dict(checks=sum(counts.values()), counts=counts, log=evidence(regression_log)),
        reusedOrdinaryEvidence="docs/craft-binding-validation-2026-10-22.json", reusedOrdinaryTestsPerTarget=825, reusedInteractionsTests=100,
        productionSourcesUnchanged=True, packages=packages, sources=[evidence(p) for p in paths], stopped=stopped, stoppedEvidence=evidence(stopped_path), runnerGuards=dict(result=guards, evidence=evidence("artifacts/basin-crash-runner-guards.json")),
        limits=["Independent native world/FTB/evidence saves are not atomic", "Other machine and storage-interior boundaries remain", "No original migration, financial or physical-client completion", "No production activation"])
    (ROOT / output).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"PASS 6 verified JVM kills, {result['boundaryChecks']} boundary/{result['recoveryChecks']} recovery checks, 263 native regression, unchanged production and package bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--stopped", required=True)
    parser.add_argument("--regression-log", required=True)
    parser.add_argument("--regression-build", required=True)
    args = parser.parse_args()
    validate(args.output, args.stopped, args.regression_log, args.regression_build)
