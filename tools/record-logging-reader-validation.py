"""Verify retained reader-blocked COMMIT reproduction, repair and native acceptance."""
from collections import Counter
from datetime import datetime
from pathlib import Path
import hashlib
import io
import json
import sqlite3
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8-sig"))


def raw(path):
    return (ROOT / path).read_text(encoding="utf-8-sig")


def evidence(path):
    path = (ROOT / path).resolve()
    data = path.read_bytes()
    return dict(path=path.relative_to(ROOT).as_posix(), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())


def files(path):
    return [evidence(p) for p in sorted((ROOT / path).rglob("*")) if p.is_file()]


def git(*args, cwd=ROOT):
    return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()


def quick_check(fixture, state=None, regression=False):
    results = []
    for relative in ["database.db", "logging-world/yuuniverse_economy/economy.db"]:
        path = ROOT / fixture / relative
        assert not any(Path(str(path) + s).exists() for s in ["-wal", "-shm", "-journal"])
        with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
            assert db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
            if state and relative == "database.db" and not regression:
                actual = []
                for table in ["blocks", "containers", "items"]:
                    columns = "m.name,r.action,0,NULL" if table == "blocks" else "m.name,r.action,r.amount,hex(r.data)"
                    query = f"SELECT r.time,r.x,r.y,r.z,{columns} FROM {table} r JOIN users u ON r.user=u.id JOIN materials m ON r.type=m.id WHERE u.uuid=? ORDER BY r.time,r.x,r.y,r.z,m.name,r.action"
                    actual.extend([[table, *row] for row in db.execute(query, (state["actor"],))])
                assert actual == state["committedRows"]
            if relative != "database.db":
                if regression:
                    receipts = db.execute("SELECT transaction_id,shop_id,currency_id,player_account_id FROM commerce_operation WHERE operation_id=?", (state["operation"],)).fetchall()
                    assert len(receipts) == 1 and receipts[0][:3] == (state["transaction"], state["shop"], state["currency"])
                    assert db.execute("SELECT balance_minor_units FROM balance_projection WHERE account_id=? AND currency_id=?", (receipts[0][3], state["currency"])).fetchall() == [(state["balance"],)]
                    assert not db.execute("SELECT transaction_id,currency_id,SUM(delta_minor_units) FROM ledger_entry GROUP BY transaction_id,currency_id HAVING SUM(delta_minor_units)<>0").fetchall()
                else:
                    assert all(db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] == 0 for table in ["ledger_transaction", "ledger_entry", "commerce_operation"])
        results.append(dict(**evidence(path), quickCheck="ok"))
    return results


def validate():
    stopped = read("artifacts/logging-reader-all-stopped.json")
    assert stopped["exclusiveStoppedWorldAndDatabases"] and len(stopped["fixtures"]) == 5
    for fixture in stopped["fixtures"]:
        assert fixture["exclusive"]
        for item in fixture["files"]:
            assert evidence(item["path"]) == item
    source = ROOT / "artifacts/grieflogger-reader-lock-source"
    provider = "artifacts/grieflogger-reader-lock-source/neoforge/build/libs/grieflogger-1.2.10-1.21.1-yuuniverse.3-neoforge.jar"
    old = read("docs/grieflogger-write-recovery-validation-2026-10-23.json")["provider"]
    assert evidence(old["path"]) == old
    assert not git("status", "--porcelain", cwd=source)
    assert git("log", "-1", "--format=%an <%ae>|%cn <%ce>", cwd=source) == "A-BenLi06 <ben_li06@outlook.com>|A-BenLi06 <ben_li06@outlook.com>"
    build_log = "artifacts/grieflogger-reader-lock-provider-build.log"
    assert "BUILD SUCCESSFUL" in raw(build_log)
    patch = "tools/grieflogger-compat/reader-commit-recovery.patch"
    subprocess.run(["git", "apply", "--reverse", "--check", str(ROOT / patch)], cwd=source, check=True)
    with zipfile.ZipFile(ROOT / provider) as after, zipfile.ZipFile(ROOT / old["path"]) as before:
        assert not any(n.startswith("org/sqlite/") for n in after.namelist())
        contents = []
        for archive in [before, after]:
            metadata = json.loads(archive.read("META-INF/jarjar/metadata.json"))
            driver = next(d for d in metadata["jars"] if d["identifier"] == dict(group="org.xerial", artifact="sqlite-jdbc"))
            with zipfile.ZipFile(io.BytesIO(archive.read(driver["path"]))) as nested:
                contents.append({n: nested.read(n) for n in nested.namelist() if not n.endswith("/")})
        assert contents[0] == contents[1]
    standalone = read("artifacts/grieflogger-reader-lock-final/validation.json")
    assert standalone["checks"] == 53
    for item in standalone["files"]:
        assert hashlib.sha256(Path(item["path"]).read_bytes()).hexdigest() == item["sha256"]
    before_log = "artifacts/grieflogger-reader-lock-baseline/tests.log"
    assert "reader_ordinary_actual_sqlite_commit_busy" in raw(before_log)
    assert "reader_ordinary_unlocked_retry_drains_original_once" in raw(before_log)
    assert "AssertionError" in raw(before_log) and "[GRIEFSTORAGE] COMPLETE" not in raw(before_log)
    for path in files("tools/grieflogger-compat/storage-tests"):
        relative = Path(path["path"]).relative_to("tools/grieflogger-compat/storage-tests")
        assert evidence(Path("artifacts/grieflogger-reader-lock-references/sources") / relative)["sha256"] == path["sha256"]
    baseline = "artifacts/logging-reader-before.log"
    assert "Native queues did not drain after unlock" in raw(baseline) and "[LOGGINGAUDIT] FAILED native storage" in raw(baseline)
    assert "GriefLogger 1.2.10-1.21.1-yuuniverse.2" in raw(baseline)
    assert "SQLiteConnection.commit" in raw(baseline) and "write outcome requires review" in raw(baseline)
    invalid_lock = "artifacts/logging-reader-invalid-lock.log"
    assert "Unknown logging storage lock mode" in raw(invalid_lock) and "Starting minecraft server" not in raw(invalid_lock)
    native_source = "tools/logging-runtime-audit/net/yuuniverse/interactions/LoggingStorageRuntimeAudit.java"
    assert evidence(native_source)["sha256"] == evidence("artifacts/grieflogger-reader-lock-references/native-sources/net/yuuniverse/interactions/LoggingStorageRuntimeAudit.java")["sha256"]
    native = []
    for name, mode in [("logging-reader-recovery", "reader"), ("logging-reader-writer-regression", "writer")]:
        fixture = Path("artifacts") / (name + "-server")
        state = read(fixture / "logging-storage-state.json")
        recovered = read(fixture / "logging-storage-recovery.json")
        assert state["lockMode"] == mode and state["checks"] == 22 and recovered["recoveryChecks"] == 11
        assert all(recovered[k] == state[k] for k in state)
        assert state["storage"] == recovered["recoveredStorage"] and state["committedRows"] == recovered["recoveredRows"]
        assert state["lockedOrdinaryPending"] == 8 and state["lockedBatchPending"] == 4
        assert Counter(row[0] for row in state["committedRows"]) == dict(blocks=5, containers=1, items=1)
        storage = state["storage"]
        assert Counter({"minecraft:paper": 7, "minecraft:oak_planks": 1}) == sum((Counter({s["id"]: s["count"]}) for s in storage["inventory"] if s), Counter())
        assert all(not slot for slot in storage["chest"])
        assert [s["Name"] for s in storage["blocks"]] == ["minecraft:stone", "create:shaft", "minecraft:chest", "minecraft:oak_planks"]
        assert storage["dropEntity"] == state["dropEntity"] and storage["dropNoGravity"]
        assert storage["dropStack"] == dict(id="minecraft:snowball", count=3, components={"minecraft:custom_name": '"Storage lock drop"'})
        assert next(s for s in storage["inventory"] if s and s["id"] == "minecraft:paper")["components"] == {"minecraft:custom_name": '"Storage lock paper"'}
        logs = [f"artifacts/{name}-{phase}.log" for phase in ["storage", "storage-recover"]]
        for path, phase, checks in zip(logs, ["storage", "storage-recover"], [22, 11]):
            text = raw(path)
            assert f"[LOGGINGSTORAGE] COMPLETE {checks} checks phase={phase}" in text and "BUILD SUCCESSFUL" in text
            assert "[LOGGINGAUDIT] FAILED" not in text and "write outcome requires review" not in text
            assert "NeoForge 21.1.248" in text and "GriefLogger 1.2.10-1.21.1-yuuniverse.3" in text
            if phase == "storage":
                assert "[SQLITE_BUSY]" in text and "Database writes rolled back; queued writes retained for retry" in text
                if mode == "reader":
                    assert "SQLiteConnection.commit" in text
        assert evidence(fixture / "mods" / Path(provider).name)["sha256"] == evidence(provider)["sha256"]
        native.append(dict(mode=mode, nativeChecks=22, restartChecks=11, state=state, recovered=recovered,
                           stateEvidence=evidence(fixture / "logging-storage-state.json"), recoveryEvidence=evidence(fixture / "logging-storage-recovery.json"),
                           logs=[evidence(p) for p in logs], providers=files(fixture / "mods"), databases=quick_check(fixture, state),
                           h2=evidence(fixture / "logging-world/neoprotect/neoprotect.mv.db")))
    regression_fixture = Path("artifacts/logging-reader-normal-regression-server")
    regression_state = read(regression_fixture / "logging-restart-state.json")
    regression_logs = [f"artifacts/logging-reader-normal-regression-{p}.log" for p in ["native", "recover"]]
    for path, marker in zip(regression_logs, ["37 checks", "8 restart checks"]):
        assert "[LOGGINGAUDIT] COMPLETE " + marker in raw(path) and "BUILD SUCCESSFUL" in raw(path)
        assert "[LOGGINGAUDIT] FAILED" not in raw(path) and "write outcome requires review" not in raw(path)
    assert evidence(regression_fixture / "mods" / Path(provider).name)["sha256"] == evidence(provider)["sha256"]
    pinned = read("artifacts/logging-reader-pinned-client-before.json")
    for item in pinned["files"]:
        assert evidence(item["path"])["sha256"].upper() == item["sha256"]
    ordinary = read("docs/craft-binding-validation-2026-10-22.json")
    assert ordinary["productionSources"] == files("neoforge/src/main")
    assert not git("diff", "b6b84b7", "--", "neoforge/src/main", "neoforge/src/dialogue", "neoforge/src/test", "neoforge/build.gradle", "tools/audit-tests", "tools/audit-tests.gradle")
    packages = []
    audit_binaries = [evidence(f"artifacts/{build}-build/classes/java/dialogue/net/yuuniverse/interactions/LoggingStorageRuntimeAudit.class")
                      for build in ["logging-reader-before", "logging-reader-recovery", "logging-reader-writer-regression", "logging-reader-normal-regression"]]
    assert len({p["sha256"] for p in audit_binaries}) == 1
    for package in ordinary["packages"]:
        assert evidence(package["path"]) == {k: package[k] for k in ["path", "bytes", "sha256"]}
        if "21.1.248-build" in package["path"] and not package["path"].endswith("-sources.jar"):
            source_set = "dialogue" if Path(package["path"]).name.startswith("yuuniverse-interactions") else "main"
            with zipfile.ZipFile(ROOT / package["path"]) as archive:
                names = [n for n in archive.namelist() if n.endswith(".class")]
                for name in names:
                    for build in ["logging-reader-before", "logging-reader-recovery", "logging-reader-writer-regression", "logging-reader-normal-regression"]:
                        assert archive.read(name) == (ROOT / f"artifacts/{build}-build/classes/java/{source_set}" / name).read_bytes(), name
            packages.append(dict(**evidence(package["path"]), matchingProductionClasses=len(names)))
    for test in ordinary["ordinary"]:
        assert test["results"] == dict(tests=825, failures=0, errors=0, skipped=0)
        for item in [test["log"], *test["reports"]]:
            assert evidence(item["path"]) == item
    assert ordinary["interactions"]["results"] == dict(tests=100, failures=0, errors=0, skipped=0)
    assert evidence(ordinary["interactions"]["log"]["path"]) == ordinary["interactions"]["log"]
    paths = ["tools/logging-runtime-audit.gradle", "tools/logging-runtime-audit/net/yuuniverse/interactions/LoggingStorageRuntimeAudit.java",
             "tools/grieflogger-compat/run-storage-tests.ps1", patch, "tools/record-logging-reader-validation.py"]
    result = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", baseRevision="b6b84b7", provider=evidence(provider),
        providerCommit=git("rev-parse", "HEAD", cwd=source), providerBase="ca00b1ac2baa71fc4baab035420d2e5bd21f0bb1",
        providerSource=[evidence(source / p) for p in git("diff", "--name-only", "ca00b1a", "HEAD", cwd=source).splitlines()],
        buildLog=evidence(build_log), unchangedNestedDriverFiles=len(contents[1]), standalone=standalone,
        baseline=dict(standaloneLog=evidence(before_log), nativeLog=evidence(baseline), sources=files("artifacts/grieflogger-reader-lock-references"),
                      databases=quick_check(Path("artifacts/logging-reader-before-server"))),
        native=native, regression=dict(nativeChecks=37, restartChecks=8, state=regression_state, logs=[evidence(p) for p in regression_logs],
            databases=quick_check(regression_fixture, regression_state, True), h2=evidence(regression_fixture / "logging-world/neoprotect/neoprotect.mv.db")),
        stopped=stopped, stoppedEvidence=evidence("artifacts/logging-reader-all-stopped.json"),
        stoppedVerifier=evidence("artifacts/confirm-logging-reader-stopped.ps1"), invalidLock=evidence(invalid_lock),
        unchangedPinnedClient=evidence("artifacts/logging-reader-pinned-client-before.json"),
        sources=[evidence(p) for p in paths] + files("tools/grieflogger-compat/storage-tests"), auditBinaries=audit_binaries, packages=packages,
        reusedOrdinaryTestsPerTarget=825, reusedInteractionsTests=100, reusedTestEvidence=evidence("docs/craft-binding-validation-2026-10-22.json"),
        limits=["Known DEFERRED SQLite BUSY COMMIT plus successful rollback only", "Other commit outcomes and driver/wrapper/mode cases remain fenced",
                "Unflushed termination, full-device faults, administration, retention/load and MySQL remain", "Migration and deferred physical-client gates remain", "No production or pinned client changes"])
    (ROOT / "docs/logging-reader-recovery-validation-2026-10-23.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("PASS reader-blocked COMMIT reproduction/repair, 53 standalone and 111 native/restart assertions")


if __name__ == "__main__":
    validate()
