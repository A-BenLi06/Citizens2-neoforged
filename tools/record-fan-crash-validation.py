"""Check actual fan world/depot/belt JVM kills and original native evidence."""
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


def amounts(values):
    result = collections.Counter()
    for value in values:
        if value:
            result[value["id"]] += value["count"]
    return dict(result)


def stored(storage, processed, holder, original=None):
    values = storage["items"]
    assert amounts(values) == ({"minecraft:paper": 6, "minecraft:diamond": 3} if processed else {"minecraft:snowball": 3})
    for value in values:
        if value and value["id"] != "minecraft:diamond":
            assert value["components"] == {"minecraft:custom_name": '"Fan crash output"' if processed else '"Fan crash input"'}
    if holder == "world":
        entities = storage["entities"]
        assert amounts([e["stack"] for e in entities]) == amounts(values)
        assert len(entities) == (2 if processed else 1)
        if original:
            primary = next(e for e in entities if e["uuid"] == original)
            assert primary["stack"]["id"] == ("minecraft:paper" if processed else "minecraft:snowball")
            assert primary["noGravity"]
    else:
        assert storage["entities"] == []


def validate(output, stopped_path, regression_log, regression_build):
    previous = read("docs/craft-binding-validation-2026-10-22.json")
    assert previous["productionSources"] == files("neoforge/src/main")
    assert subprocess.run(["git", "diff", "--quiet", "2d4c17c", "--", "neoforge/src/main", "neoforge/src/dialogue", "neoforge/src/test", "neoforge/build.gradle", "tools/audit-tests", "tools/audit-tests.gradle"], cwd=ROOT).returncode == 0
    stopped = read(stopped_path)
    assert stopped["exclusiveStoppedWorldAndJournalOwnership"] and len(stopped["fixtures"]) == 19
    seen = {key: set() for key in ["run", "entry", "actor", "machine"]}
    cases = []
    for target in ["21.1.248", "21.1.233"]:
        for holder in ["world", "depot", "belt"]:
            for stage in ["prepared", "confirmed", "applied"]:
                prefix = f"artifacts/fan-crash-{target.replace('.', '-')}-{holder}-{stage}"
                fixture = Path(f"artifacts/fan-crash-{target}-{holder}-{stage}-server")
                assert fixture.as_posix() in stopped["fixtures"]
                state = read(prefix + "-state.json")
                termination = read(prefix + "-termination.json")
                recovered = read(fixture / "fan-crash-recovery.json")
                assert all(state[key] == recovered[key] for key in state)
                for key in seen:
                    assert state[key] not in seen[key]
                    seen[key].add(state[key])
                assert state["stage"] == stage and state["type"] == holder and state["entries"] == 1 and state["maximum"] == 1
                assert termination["stage"] == stage and termination["neoForge"] == target and termination["machine"] == "fan-" + holder
                assert termination["forcedTermination"] and termination["run"] == state["run"] and termination["entry"] == state["entry"]
                assert Path(termination["fixture"]).resolve() == (ROOT / fixture).resolve()
                build = ROOT / f"artifacts/fan-crash-{target}-build"
                assert Path(termination["verifiedArgumentFile"]).resolve() == build / "moddev/serverRunProgramArgs.txt"
                assert Path(termination["projectCache"]).resolve() == ROOT / f"artifacts/fan-crash-{target}-cache"
                assert datetime.fromisoformat(termination["processCreatedAt"]) <= datetime.fromisoformat(termination["killedAt"])
                killed = (ROOT / (prefix + "-killed.log")).read_text(encoding="utf-8-sig")
                killed_err = (ROOT / (prefix + "-killed-stderr.log")).read_text(encoding="utf-8-sig")
                marker = f"[MACHINECRASHAUDIT] KILL_READY pid={termination['pid']} entry={state['entry']} run={state['run']}"
                assert marker in killed and f"NeoForge {target}" in killed and "Stopping server" not in killed and "[CREATEAUDIT] FAILED" not in killed
                assert "BUILD FAILED" in killed + killed_err and "BUILD SUCCESSFUL" not in killed
                boundary_checks = len(re.findall(r"\[FANCRASHAUDIT\] PASS ", killed))
                assert boundary_checks == {"prepared": 3, "confirmed": 4, "applied": 5}[stage]
                recovery_log = (ROOT / (prefix + "-recovered.log")).read_text(encoding="utf-8-sig")
                checks = (12 if stage == "applied" else 13) if holder == "world" else (11 if stage == "applied" else 12)
                assert f"[FANCRASHAUDIT] COMPLETE {checks} checks type={holder} stage={stage}" in recovery_log
                assert "BUILD SUCCESSFUL" in recovery_log and f"NeoForge {target}" in recovery_log and "[CREATEAUDIT] FAILED" not in recovery_log
                original = state["savedStorage"]["entities"][0]["uuid"] if holder == "world" else None
                stored(state["savedStorage"], False, holder, original)
                stored(state["liveStorage"], stage != "prepared", holder, original)
                stored(recovered["recoveredStorage"], True, holder, original)
                assert recovered["recoveredProgress"] == (1 if stage == "confirmed" else 0)
                payload = state["evidence"]
                assert payload["recipeId"] == "citizens_audit:fan_crash" and payload["recipeRevision"] == state["revision"] and payload["source"] == "CREATE_FAN"
                assert payload["attribution"]["machineId"] == state["machine"] and payload["attribution"]["beneficiaryId"] == state["actor"]
                assert payload["consumedInput"] == dict(id="minecraft:snowball", count=3, components={"minecraft:custom_name": '"Fan crash input"'})
                assert amounts(payload["rolledOutputs"]) == {"minecraft:paper": 6, "minecraft:diamond": 3}
                assert len(payload["rolledOutputs"]) == 6
                for rolled in payload["rolledOutputs"]:
                    assert rolled == (dict(id="minecraft:paper", count=2, components={"minecraft:custom_name": '"Fan crash output"'})
                                      if rolled["id"] == "minecraft:paper" else dict(id="minecraft:diamond", count=1))
                if original:
                    assert payload["inputEntityId"] == original
                else:
                    assert "inputEntityId" not in payload
                heads = recovered["recoveredEntries"]
                assert len(heads) == 1 and heads[0]["id"] == state["entry"] and heads[0]["revision"] == 2
                assert heads[0]["state"] == ("REJECTED" if stage == "prepared" else "APPLIED")
                assert heads[0]["admission"]["actorId"] == state["actor"] and heads[0]["admission"]["units"] == 6
                assert json.loads(heads[0]["admission"]["evidence"]) == payload
                journal = fixture / state["evidenceDirectory"] / "journal"
                records = [read(p)["payload"] for p in sorted((ROOT / journal).glob(f"entry-{state['entry']}-*.json"))]
                assert [e["state"] for e in records] == (["PREPARED", "REVIEW", "REJECTED"] if stage == "prepared" else ["PREPARED", "CONFIRMED", "APPLIED"])
                assert all(e["admission"] == records[0]["admission"] for e in records)
                assert records[-1] == {key: heads[0].get(key) for key in records[-1]}
                binding = read(fixture / state["evidenceDirectory"] / "bindings" / (state["task"] + ".json"))
                generation = read(journal / ("generation-" + heads[0]["admission"]["generationId"] + ".json"))["payload"]
                assert generation["scope"] == dict(epoch=binding["epoch"], teamId=state["team"], questId=binding["questId"])
                assert generation["nativeCycle"] == 0 and heads[0]["admission"]["taskId"] == state["task"]
                assert binding["taskId"] == state["task"] and binding["maximum"] == 1 and not binding["repeatable"]
                assert binding["recipeId"] == payload["recipeId"] and binding["recipeRevision"] == payload["recipeRevision"] and binding["operatorId"] == state["actor"]
                team = fixture / f"create-world/ftbquests/{state['team']}.snbt"
                progress = re.search(r"task_progress:\s*\{([^}]*)\}", (ROOT / team).read_text(encoding="utf-8-sig"))
                persisted = {} if progress is None else {k: int(v) for k, v in re.findall(r"([0-9A-F]{16}):\s*(\d+)L", progress[1])}
                assert persisted.get(state["task"], 0) == recovered["recoveredProgress"]
                providers = files(fixture / "mods")
                assert {Path(p["path"]).name: p["sha256"] for p in providers} == {Path(p["path"]).name: p["sha256"] for p in previous["create"]["providers"]}
                cases.append(dict(target=target, holder=holder, stage=stage, boundaryChecks=boundary_checks, recoveryChecks=checks, state=state,
                    logs=[evidence(prefix + suffix) for suffix in ["-killed.log", "-killed-stderr.log", "-recovered.log"]], termination=evidence(prefix + "-termination.json"),
                    savedState=evidence(prefix + "-state.json"), recovery=evidence(fixture / "fan-crash-recovery.json"), journal=files(fixture / state["evidenceDirectory"]),
                    team=evidence(team), providers=providers, scripts=files(fixture / "kubejs/server_scripts"), auditBinary=evidence(build / "classes/java/dialogue/net/yuuniverse/interactions/FanCrashRuntimeAudit.class")))
    counts = dict(CREATEBATCHAUDIT=23, CREATEPRESSAUDIT=16, CREATETRANSPORTAUDIT=21, CREATEBASINAUDIT=23, CREATEFLUIDAUDIT=28, CREATEDEPLOYERAUDIT=26, CREATEFANAUDIT=31, CREATEASSEMBLYAUDIT=41, CREATEAUDIT=54)
    regression = (ROOT / regression_log).read_text(encoding="utf-8-sig")
    assert "BUILD SUCCESSFUL" in regression and "[CREATEAUDIT] FAILED" not in regression
    for name, count in counts.items():
        assert f"[{name}] COMPLETE {count} checks" in regression
    preparation_log = "artifacts/fan-conflicting-selections.log"
    assert (ROOT / preparation_log).read_text(encoding="utf-8-sig").strip() == "PASS all three conflicting machine-family selections reject before fixture creation"
    assert not (ROOT / "artifacts/fan-conflicting-selection-must-not-exist").exists()
    assert not (ROOT / "artifacts/fan-recovery-regression-server/kubejs/server_scripts/citizens_fan_crash.js").exists()
    packages = []
    for package in previous["packages"]:
        assert evidence(package["path"]) == {k: package[k] for k in ["path", "bytes", "sha256"]}
        if package["path"].endswith("-sources.jar"):
            continue
        target = "21.1.248" if "21.1.248-build" in package["path"] else "21.1.233"
        source_set = "dialogue" if Path(package["path"]).name.startswith("yuuniverse-interactions") else "main"
        with zipfile.ZipFile(ROOT / package["path"]) as archive:
            names = [n for n in archive.namelist() if n.endswith(".class")]
            for name in names:
                assert archive.read(name) == (ROOT / f"artifacts/fan-crash-{target}-build/classes/java/{source_set}" / name).read_bytes(), name
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
    paths = ["tools/create-runtime-audit/net/yuuniverse/interactions/FanCrashRuntimeAudit.java", "tools/create-runtime-audit/net/yuuniverse/interactions/CreateRuntimeAudit.java", "tools/prepare-create-audit.ps1", "tools/run-machine-crash-audit.ps1", "tools/create-runtime-audit.gradle", "tools/record-fan-crash-validation.py", "artifacts/run-fan-crash-matrix.ps1", "artifacts/run-fan-recovery-regression.ps1", "artifacts/check-fan-crash-stopped.ps1"]
    result = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", baseRevision="2d4c17c", cases=cases, actualJvmKills=18,
        boundaryChecks=sum(c["boundaryChecks"] for c in cases), recoveryChecks=sum(c["recoveryChecks"] for c in cases), regression=dict(checks=sum(counts.values()), counts=counts, log=evidence(regression_log)),
        reusedOrdinaryEvidence="docs/craft-binding-validation-2026-10-22.json", reusedOrdinaryTestsPerTarget=825, reusedInteractionsTests=100, productionSourcesUnchanged=True,
        packages=packages, sources=[evidence(p) for p in paths], stopped=stopped, stoppedEvidence=evidence(stopped_path),
        preparation=dict(conflictingFamiliesRejectBeforeMutation=evidence(preparation_log), normalPreparationRemovesFanOnlyRecipe=True),
        limits=["SPLASHING and the three named holder paths/boundaries", "Independent native/evidence/FTB/money saves are not atomic", "Other fan modes/storage-interior/party/financial boundaries remain", "Migration/provider/physical-client gates remain"])
    (ROOT / output).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"PASS 18 verified JVM kills, {result['boundaryChecks']} boundary/{result['recoveryChecks']} recovery checks, 263 native regression and unchanged production/package bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--stopped", required=True)
    parser.add_argument("--regression-log", required=True)
    parser.add_argument("--regression-build", required=True)
    args = parser.parse_args()
    validate(args.output, args.stopped, args.regression_log, args.regression_build)
