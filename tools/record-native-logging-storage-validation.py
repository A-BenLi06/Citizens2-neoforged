"""Record actual in-server SQLite lock/retry and exact native restart evidence."""
from pathlib import Path
from datetime import datetime
from collections import Counter
import hashlib
import json
import sqlite3
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8-sig"))


def evidence(path):
    path = (ROOT / path).resolve()
    data = path.read_bytes()
    return dict(path=path.relative_to(ROOT).as_posix(), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())


def files(path):
    return [evidence(p) for p in sorted((ROOT / path).rglob("*")) if p.is_file()]


def validate():
    fixture = Path("artifacts/logging-native-lock-server")
    state = read(fixture / "logging-storage-state.json")
    recovered = read(fixture / "logging-storage-recovery.json")
    assert all(state[key] == recovered[key] for key in state)
    assert state["checks"] == 22 and recovered["recoveryChecks"] == 11
    assert state["lockedOrdinaryPending"] == 8 and state["lockedBatchPending"] == 4
    assert state["storage"] == recovered["recoveredStorage"] and state["committedRows"] == recovered["recoveredRows"]
    storage = state["storage"]
    assert Counter({"minecraft:paper": 7, "minecraft:oak_planks": 1}) == sum((Counter({s["id"]: s["count"]}) for s in storage["inventory"] if s), Counter())
    assert all(not slot for slot in storage["chest"])
    assert [s["Name"] for s in storage["blocks"]] == ["minecraft:stone", "create:shaft", "minecraft:chest", "minecraft:oak_planks"]
    assert storage["dropEntity"] == state["dropEntity"] and storage["dropNoGravity"]
    assert storage["dropStack"] == dict(id="minecraft:snowball", count=3, components={"minecraft:custom_name": '"Storage lock drop"'})
    assert next(s for s in storage["inventory"] if s and s["id"] == "minecraft:paper")["components"] == {"minecraft:custom_name": '"Storage lock paper"'}
    assert len(state["beforeLockRows"]) == 1 and state["beforeLockRows"][0][2:8] == [7, -60, 3, "chest", 1, 0]
    assert Counter(row[0] for row in state["committedRows"]) == dict(blocks=5, containers=1, items=1)
    assert Counter((row[5], row[6], row[7]) for row in state["committedRows"]) == Counter({("chest",1,0):1, ("chest",2,0):1, ("stone",1,0):1, ("create:shaft",1,0):1, ("oak_planks",1,0):1, ("paper",0,7):1, ("snowball",2,3):1})
    logs = ["artifacts/logging-native-lock.log", "artifacts/logging-native-lock-restart.log"]
    for path, phase, count in zip(logs, ["storage", "storage-recover"], [22, 11]):
        raw = (ROOT / path).read_text(encoding="utf-8-sig")
        assert f"[LOGGINGSTORAGE] COMPLETE {count} checks phase={phase}" in raw
        assert "BUILD SUCCESSFUL" in raw and "[LOGGINGAUDIT] FAILED" not in raw
        assert "NeoForge 21.1.248" in raw and "GriefLogger 1.2.10-1.21.1-yuuniverse.2" in raw
        assert "write outcome requires review" not in raw
        if phase == "storage":
            assert "[SQLITE_BUSY]" in raw and "Database writes rolled back; queued writes retained for retry" in raw
            assert "[ForkJoinPool-" in raw and "[Server thread/ERROR] [co.da.gr.GriefLogger/]" in raw
    stopped_path = "artifacts/logging-native-lock-stopped.json"
    stopped = read(stopped_path)
    assert stopped["exclusiveStoppedWorldAndDatabases"] and Path(stopped["fixture"]).resolve() == (ROOT / fixture).resolve()
    guard_path = "artifacts/logging-native-lock-repeat-guards.json"
    guards = read(guard_path)
    assert guards["unchanged"] and guards["launchGuardProtected"] and len(guards["checks"]) == 4
    assert guards["before"] == guards["after"]
    for path, before in zip(guards["protected"], guards["before"]):
        assert evidence(path)["sha256"].upper() == before
    for guard in guards["checks"]:
        raw = Path(guard["log"]).read_text(encoding="utf-8-sig")
        assert guard["exitCode"] != 0 and guard["expected"] in raw and "Starting minecraft server" not in raw
        if guard.get("prepareTaskSkipped"):
            assert "Execution failed for task ':runServer'" in raw
    assert sum(bool(g.get("prepareTaskSkipped")) for g in guards["checks"]) == 2
    databases = []
    for relative in ["database.db", "logging-world/yuuniverse_economy/economy.db"]:
        path = ROOT / fixture / relative
        assert not any(Path(str(path) + suffix).exists() for suffix in ["-wal", "-shm", "-journal"])
        with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
            assert db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
            if relative == "database.db":
                actual = []
                for table in ["blocks", "containers", "items"]:
                    columns = "m.name,r.action,0,NULL" if table == "blocks" else "m.name,r.action,r.amount,hex(r.data)"
                    query = f"SELECT r.time,r.x,r.y,r.z,{columns} FROM {table} r JOIN users u ON r.user=u.id JOIN materials m ON r.type=m.id WHERE u.uuid=? ORDER BY r.time,r.x,r.y,r.z,m.name,r.action"
                    actual.extend([[table, *row] for row in db.execute(query, (state["actor"],))])
                assert actual == state["committedRows"]
            else:
                assert all(db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] == 0 for table in ["ledger_transaction", "ledger_entry", "commerce_operation"])
        databases.append(dict(**evidence(path), quickCheck="ok"))
    accepted = read("docs/grieflogger-write-recovery-validation-2026-10-23.json")
    providers = files(fixture / "mods")
    assert {Path(p["path"]).name: p["sha256"] for p in providers} == {Path(p["path"]).name: p["sha256"] for p in accepted["providers"]}
    assert evidence(accepted["provider"]["path"]) == accepted["provider"]
    for log in accepted["logs"]:
        assert evidence(log["path"]) == log
    regression_fixture = Path("artifacts/logging-native-lock-regression-server")
    regression_logs = [f"artifacts/logging-native-lock-regression-{phase}.log" for phase in ["native", "recover"]]
    for path, count in zip(regression_logs, ["37 checks", "8 restart checks"]):
        raw = (ROOT / path).read_text(encoding="utf-8-sig")
        assert f"[LOGGINGAUDIT] COMPLETE {count}" in raw and "BUILD SUCCESSFUL" in raw and "[LOGGINGAUDIT] FAILED" not in raw
        assert "Database writes rolled back;" not in raw and "write outcome requires review" not in raw
    assert {Path(p["path"]).name: p["sha256"] for p in files(regression_fixture / "mods")} == {Path(p["path"]).name: p["sha256"] for p in providers}
    regression_state = read(regression_fixture / "logging-restart-state.json")
    for relative in ["database.db", "logging-world/yuuniverse_economy/economy.db"]:
        path = ROOT / regression_fixture / relative
        assert not any(Path(str(path) + suffix).exists() for suffix in ["-wal", "-shm", "-journal"])
        with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
            assert db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
            if path.name == "economy.db":
                receipt = db.execute("SELECT transaction_id,shop_id,currency_id,player_account_id FROM commerce_operation WHERE operation_id=?", (regression_state["operation"],)).fetchall()
                assert len(receipt) == 1 and receipt[0][:3] == (regression_state["transaction"], regression_state["shop"], regression_state["currency"])
                assert db.execute("SELECT balance_minor_units FROM balance_projection WHERE account_id=? AND currency_id=?", (receipt[0][3], regression_state["currency"])).fetchall() == [(regression_state["balance"],)]
                assert db.execute("SELECT transaction_id,currency_id,SUM(delta_minor_units) FROM ledger_entry GROUP BY transaction_id,currency_id HAVING SUM(delta_minor_units)<>0").fetchall() == []
        databases.append(dict(**evidence(path), quickCheck="ok"))
    all_stopped_path = "artifacts/logging-native-lock-all-stopped.json"
    all_stopped = read(all_stopped_path)
    assert all_stopped["exclusiveStoppedWorldAndDatabases"] and len(all_stopped["fixtures"]) == 2
    ordinary = read("docs/craft-binding-validation-2026-10-22.json")
    assert ordinary["productionSources"] == files("neoforge/src/main")
    assert subprocess.run(["git", "diff", "--quiet", "296bb81", "--", "neoforge/src/main", "neoforge/src/dialogue", "neoforge/src/test", "neoforge/build.gradle", "tools/audit-tests", "tools/audit-tests.gradle"], cwd=ROOT).returncode == 0
    packages = []
    for package in ordinary["packages"]:
        assert evidence(package["path"]) == {k: package[k] for k in ["path", "bytes", "sha256"]}
        if "21.1.248-build" in package["path"] and not package["path"].endswith("-sources.jar"):
            source_set = "dialogue" if Path(package["path"]).name.startswith("yuuniverse-interactions") else "main"
            with zipfile.ZipFile(ROOT / package["path"]) as archive:
                names = [n for n in archive.namelist() if n.endswith(".class")]
                for name in names:
                    assert archive.read(name) == (ROOT / f"artifacts/logging-native-lock-build/classes/java/{source_set}" / name).read_bytes(), name
                    assert archive.read(name) == (ROOT / f"artifacts/logging-native-lock-regression-build/classes/java/{source_set}" / name).read_bytes(), name
            packages.append(dict(**evidence(package["path"]), matchingProductionClasses=len(names)))
    for test in ordinary["ordinary"]:
        assert test["results"] == dict(tests=825, failures=0, errors=0, skipped=0)
        for item in [test["log"], *test["reports"]]:
            assert evidence(item["path"]) == item
    assert ordinary["interactions"]["results"] == dict(tests=100, failures=0, errors=0, skipped=0)
    assert evidence(ordinary["interactions"]["log"]["path"]) == ordinary["interactions"]["log"]
    paths = ["tools/logging-runtime-audit.gradle", "tools/logging-runtime-audit/net/yuuniverse/interactions/LoggingRuntimeAudit.java", "tools/logging-runtime-audit/net/yuuniverse/interactions/LoggingStorageRuntimeAudit.java", "tools/record-native-logging-storage-validation.py"]
    result = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", baseRevision="296bb81", nativeChecks=22, restartChecks=11,
        state=state, recovered=recovered, stateEvidence=evidence(fixture / "logging-storage-state.json"), recoveryEvidence=evidence(fixture / "logging-storage-recovery.json"),
        logs=[evidence(p) for p in logs], providers=providers, providerAcceptance=evidence("docs/grieflogger-write-recovery-validation-2026-10-23.json"),
        databases=databases, h2=evidence(fixture / "logging-world/neoprotect/neoprotect.mv.db"), stopped=stopped, stoppedEvidence=evidence(stopped_path),
        regression=dict(nativeChecks=37, restartChecks=8, logs=[evidence(p) for p in regression_logs], state=regression_state,
                        stateEvidence=evidence(regression_fixture / "logging-restart-state.json"), h2=evidence(regression_fixture / "logging-world/neoprotect/neoprotect.mv.db")),
        repeatGuards=guards, repeatGuardEvidence=evidence(guard_path), repeatGuardLogs=[evidence(g["log"]) for g in guards["checks"]],
        allStopped=all_stopped, allStoppedEvidence=evidence(all_stopped_path),
        sources=[evidence(p) for p in paths], auditBinary=evidence("artifacts/logging-native-lock-build/classes/java/dialogue/net/yuuniverse/interactions/LoggingStorageRuntimeAudit.class"),
        packages=packages, reusedOrdinaryTestsPerTarget=825, reusedInteractionsTests=100, reusedTestEvidence="docs/craft-binding-validation-2026-10-22.json",
        limits=["Actual in-server SQLite writer lock with ordinary, container and dropped-item native producers", "Known rollback/retry followed by clean restart, not unflushed process-kill durability", "Other failures, uncertain-outcome administration, retention/capacity/load and MySQL remain", "Migration/provider and deferred physical-client gates remain", "No production or pinned client fixture changes"])
    (ROOT / "docs/native-logging-storage-validation-2026-10-23.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("PASS 22 actual native SQLite-outage checks / 11 restart checks, exact seven original records/storage and unchanged production packages")


if __name__ == "__main__":
    validate()
