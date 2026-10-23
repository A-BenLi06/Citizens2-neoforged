"""Verify actual SQLite FULL reproduction, repair and retained native evidence."""
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


def database_evidence(fixture, state=None, commerce=False):
    results = []
    for relative in ["database.db", "logging-world/yuuniverse_economy/economy.db"]:
        path = ROOT / fixture / relative
        assert not any(Path(str(path) + suffix).exists() for suffix in ["-wal", "-shm", "-journal"])
        with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
            assert db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
            if relative == "database.db" and state and not commerce:
                actual = []
                for table in ["blocks", "containers", "items"]:
                    columns = "m.name,r.action,0,NULL" if table == "blocks" else "m.name,r.action,r.amount,hex(r.data)"
                    query = f"SELECT r.time,r.x,r.y,r.z,{columns} FROM {table} r JOIN users u ON r.user=u.id JOIN materials m ON r.type=m.id WHERE u.uuid=? ORDER BY r.time,r.x,r.y,r.z,m.name,r.action"
                    actual.extend([[table, *row] for row in db.execute(query, (state["actor"],))])
                assert actual == state["committedRows"]
                assert not db.execute("SELECT name FROM sqlite_master WHERE name LIKE 'audit_capacity_%'").fetchall()
                if state["lockMode"] == "full":
                    assert state["actor"] != state["capacityPaddingActor"]
                    for table, count in state["capacityPaddingCounts"].items():
                        assert count > 0
                        assert db.execute(f"SELECT COUNT(*) FROM {table} r JOIN users u ON r.user=u.id WHERE u.uuid=?", (state["capacityPaddingActor"],)).fetchone()[0] == count
            if relative != "database.db":
                if commerce:
                    receipts = db.execute("SELECT transaction_id,shop_id,currency_id,player_account_id FROM commerce_operation WHERE operation_id=?", (state["operation"],)).fetchall()
                    assert len(receipts) == 1 and receipts[0][:3] == (state["transaction"], state["shop"], state["currency"])
                    assert db.execute("SELECT balance_minor_units FROM balance_projection WHERE account_id=? AND currency_id=?", (receipts[0][3], state["currency"])).fetchall() == [(state["balance"],)]
                    assert not db.execute("SELECT transaction_id,currency_id,SUM(delta_minor_units) FROM ledger_entry GROUP BY transaction_id,currency_id HAVING SUM(delta_minor_units)<>0").fetchall()
                else:
                    assert all(db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] == 0 for table in ["ledger_transaction", "ledger_entry", "commerce_operation"])
        results.append(dict(**evidence(path), quickCheck="ok"))
    return results


def validate():
    source = ROOT / "artifacts/grieflogger-full-recovery-source"
    provider = "artifacts/grieflogger-full-recovery-source/neoforge/build/libs/grieflogger-1.2.10-1.21.1-yuuniverse.4-neoforge.jar"
    base = "5c3ed69d4bddfdc3b2451f9a7d1ef7e7b8d3b19b"
    patch = "tools/grieflogger-compat/sqlite-full-recovery.patch"
    assert not git("status", "--porcelain", cwd=source)
    assert git("log", "-1", "--format=%an <%ae>|%cn <%ce>", cwd=source) == "A-BenLi06 <ben_li06@outlook.com>|A-BenLi06 <ben_li06@outlook.com>"
    subprocess.run(["git", "apply", "--reverse", "--check", str(ROOT / patch)], cwd=source, check=True)
    subprocess.run(["git", "apply", "--check", str(ROOT / patch)], cwd=ROOT / "artifacts/grieflogger-reader-lock-source", check=True)
    assert "BUILD SUCCESSFUL" in raw("artifacts/grieflogger-full-provider-build.log")
    old = read("docs/logging-reader-recovery-validation-2026-10-23.json")["provider"]
    assert evidence(old["path"]) == old
    driver_contents = []
    for name in [old["path"], provider]:
        with zipfile.ZipFile(ROOT / name) as archive:
            assert not any(n.startswith("org/sqlite/") for n in archive.namelist())
            metadata = json.loads(archive.read("META-INF/jarjar/metadata.json"))
            drivers = [d for d in metadata["jars"] if d["identifier"] == dict(group="org.xerial", artifact="sqlite-jdbc")]
            assert len(drivers) == 1
            with zipfile.ZipFile(io.BytesIO(archive.read(drivers[0]["path"]))) as nested:
                driver_contents.append({n: nested.read(n) for n in nested.namelist() if not n.endswith("/")})
    assert driver_contents[0] == driver_contents[1]
    standalone = read("artifacts/grieflogger-full-final/validation.json")
    assert standalone["checks"] == 93 and standalone["actualProviderBytecode"]
    for item in standalone["files"]:
        assert hashlib.sha256(Path(item["path"]).read_bytes()).hexdigest() == item["sha256"]
    standalone_databases = []
    for path in sorted((ROOT / "artifacts/grieflogger-full-final/databases").glob("*.db")):
        assert not any(Path(str(path) + suffix).exists() for suffix in ["-wal", "-shm", "-journal"])
        with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
            assert db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
            if path.stem in ["full_ordinary", "full_batch"]:
                expected = [(1, "original-prefix", bytes([3, 7])),
                            (2, None, bytes((i * 31) % 256 for i in range(32768))),
                            (3, "later", bytes([9]))]
                assert db.execute("SELECT id,value,data FROM observations ORDER BY id").fetchall() == expected
            count = db.execute("SELECT COUNT(*) FROM observations").fetchone()[0]
        standalone_databases.append(dict(**evidence(path), quickCheck="ok", retainedRows=count))
    assert len(standalone_databases) == 21
    before = raw("artifacts/grieflogger-full-before/tests.log")
    assert "PASS full_ordinary_native_auto_rollback_retains_entire_original_prefix_attempt_1" in before
    assert "AssertionError: full_ordinary_actual_sqlite_full_attempt_2" in before and "[GRIEFSTORAGE] COMPLETE" not in before
    diagnostic = raw("artifacts/grieflogger-full-diagnostic/diagnostic.log")
    assert "NATIVE ROLLBACK" in diagnostic and "after capacity restore rows=0 pending=2" in diagnostic
    baseline = raw("artifacts/logging-full-direct-before-storage.log")
    assert "[SQLITE_FULL]" in baseline and "write outcome requires review" in baseline
    assert "cannot rollback - no transaction is active" in baseline and "Native queues did not drain after unlock" in baseline
    assert "native_capacity_restored_without_replacing_or_resubmitting_queued_observations" in baseline
    native = []
    for name, mode, checks in [("logging-full-final", "full", 27), ("logging-full-reader-regression", "reader", 22), ("logging-full-writer-regression", "writer", 22)]:
        fixture = Path("artifacts") / (name + "-server")
        state = read(fixture / "logging-storage-state.json")
        recovered = read(fixture / "logging-storage-recovery.json")
        assert state["lockMode"] == mode and state["checks"] == checks and recovered["recoveryChecks"] == 11
        assert all(recovered[k] == state[k] for k in state)
        assert state["storage"] == recovered["recoveredStorage"] and state["committedRows"] == recovered["recoveredRows"]
        assert state["lockedOrdinaryPending"] > 0 and state["lockedBatchPending"] > 0
        assert Counter(row[0] for row in state["committedRows"]) == dict(blocks=5, containers=1, items=1)
        storage = state["storage"]
        assert Counter({"minecraft:paper": 7, "minecraft:oak_planks": 1}) == sum((Counter({s["id"]: s["count"]}) for s in storage["inventory"] if s), Counter())
        assert all(not slot for slot in storage["chest"])
        assert [s["Name"] for s in storage["blocks"]] == ["minecraft:stone", "create:shaft", "minecraft:chest", "minecraft:oak_planks"]
        assert storage["dropEntity"] == state["dropEntity"] and storage["dropNoGravity"]
        assert storage["dropStack"] == dict(id="minecraft:snowball", count=3, components={"minecraft:custom_name": '"Storage lock drop"'})
        assert next(s for s in storage["inventory"] if s and s["id"] == "minecraft:paper")["components"] == {"minecraft:custom_name": '"Storage lock paper"'}
        logs = [f"artifacts/{name}-{phase}.log" for phase in ["storage", "storage-recover"]]
        for path, phase, count in zip(logs, ["storage", "storage-recover"], [checks, 11]):
            text = raw(path)
            assert f"[LOGGINGSTORAGE] COMPLETE {count} checks phase={phase}" in text and "BUILD SUCCESSFUL" in text
            assert "[LOGGINGAUDIT] FAILED" not in text and "write outcome requires review" not in text
            assert "NeoForge 21.1.248" in text and "GriefLogger 1.2.10-1.21.1-yuuniverse.4" in text
            if phase == "storage":
                assert ("[SQLITE_FULL]" if mode == "full" else "[SQLITE_BUSY]") in text
                if mode == "full":
                    assert "SQLiteConnection.rollback" not in text
        assert evidence(fixture / "mods" / Path(provider).name)["sha256"] == evidence(provider)["sha256"]
        native.append(dict(mode=mode, checks=checks, restartChecks=11, state=state, recovered=recovered,
                           stateEvidence=evidence(fixture / "logging-storage-state.json"), recoveryEvidence=evidence(fixture / "logging-storage-recovery.json"),
                           logs=[evidence(p) for p in logs], providers=files(fixture / "mods"), databases=database_evidence(fixture, state),
                           h2=evidence(fixture / "logging-world/neoprotect/neoprotect.mv.db")))
    fixture = Path("artifacts/logging-full-normal-regression-server")
    state = read(fixture / "logging-restart-state.json")
    logs = [f"artifacts/logging-full-normal-regression-{p}.log" for p in ["native", "recover"]]
    for path, marker in zip(logs, ["37 checks", "8 restart checks"]):
        text = raw(path)
        assert "[LOGGINGAUDIT] COMPLETE " + marker in text and "BUILD SUCCESSFUL" in text
        assert "[LOGGINGAUDIT] FAILED" not in text and "write outcome requires review" not in text
    assert evidence(fixture / "mods" / Path(provider).name)["sha256"] == evidence(provider)["sha256"]
    regression = dict(nativeChecks=37, restartChecks=8, state=state, logs=[evidence(p) for p in logs],
                      providers=files(fixture / "mods"), databases=database_evidence(fixture, state, True),
                      h2=evidence(fixture / "logging-world/neoprotect/neoprotect.mv.db"))
    previous = read("docs/craft-index-validation-2026-10-23.json")
    assert not git("diff", "0cd4535", "--", "neoforge/src/main", "neoforge/src/dialogue", "neoforge/src/test", "neoforge/build.gradle", "tools/audit-tests", "tools/audit-tests.gradle")
    packages = []
    builds = ["logging-full-direct-before", "logging-full-final", "logging-full-reader-regression", "logging-full-writer-regression", "logging-full-normal-regression"]
    binaries = [evidence(f"artifacts/{name}-build/classes/java/dialogue/net/yuuniverse/interactions/LoggingStorageRuntimeAudit.class") for name in builds]
    assert len({item["sha256"] for item in binaries}) == 1
    for package in previous["packages"]:
        assert evidence(package["path"]) == {k: package[k] for k in ["path", "bytes", "sha256"]}
        if "21.1.248-build" in package["path"] and not package["path"].endswith("-sources.jar"):
            source_set = "dialogue" if Path(package["path"]).name.startswith("yuuniverse-interactions") else "main"
            with zipfile.ZipFile(ROOT / package["path"]) as archive:
                names = [n for n in archive.namelist() if n.endswith(".class")]
                for name in names:
                    for build in builds:
                        assert archive.read(name) == (ROOT / f"artifacts/{build}-build/classes/java/{source_set}" / name).read_bytes(), name
            packages.append(dict(**evidence(package["path"]), matchingProductionClasses=len(names)))
    for test in previous["ordinary"] + [previous["interactions"]]:
        assert test["results"] == dict(tests=100 if test is previous["interactions"] else 828, failures=0, errors=0, skipped=0)
        for item in [test["log"], *test["reports"]]:
            assert evidence(item["path"]) == item
    client = previous["pinnedClientReference"]
    assert evidence(client["path"]) == client
    for item in read(client["path"])["files"]:
        assert evidence(item["path"])["sha256"].upper() == item["sha256"]
    stopped = read("artifacts/logging-full-stopped.json")
    assert len(stopped["fixtures"]) == 7 and stopped["exclusiveStoppedWorldAndDatabases"]
    for record in stopped["fixtures"]:
        assert record["exclusive"]
        for item in record["files"]:
            assert evidence(item["path"]) == item
    guards = read("artifacts/logging-full-launch-rejections.json")
    assert guards["before"] == guards["after"] and len(guards["checks"]) == 4
    for path, digest in guards["before"].items():
        assert evidence(path)["sha256"].upper() == digest
    for check in guards["checks"]:
        assert check["exitCode"] != 0 and check["expected"] in raw(check["log"]) and "Starting minecraft server" not in raw(check["log"])
    sources = ["tools/logging-runtime-audit.gradle", "tools/grieflogger-compat/run-storage-tests.ps1"]
    sources += [p["path"] for p in files("tools/logging-runtime-audit") + files("tools/grieflogger-compat/storage-tests") if p["path"].endswith(".java")]
    for name in sources:
        frozen = ROOT / "artifacts/logging-full-references" / Path(name).relative_to("tools")
        assert frozen.read_bytes() == (ROOT / name).read_bytes()
    result = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", baseRevision="0cd4535",
                  provider=evidence(provider), providerCommit=git("rev-parse", "HEAD", cwd=source), providerBase=base,
                  providerSource=[evidence(source / p) for p in git("diff", "--name-only", base, "HEAD", cwd=source).splitlines()],
                  patch=evidence(patch), providerBuild=evidence("artifacts/grieflogger-full-provider-build.log"), unchangedNestedDriverFiles=len(driver_contents[1]),
                  standalone=standalone, standaloneDatabases=standalone_databases,
                  baseline=dict(standalone=evidence("artifacts/grieflogger-full-before/tests.log"),
                      diagnostic=files("artifacts/grieflogger-full-diagnostic"), native=evidence("artifacts/logging-full-direct-before-storage.log"),
                      databases=database_evidence(Path("artifacts/logging-full-direct-before-server"))),
                  rejectedTriggerFixture=dict(log=evidence("artifacts/logging-full-before-storage.log"), sources=files("artifacts/logging-full-trigger-diagnostic-sources"),
                      databases=database_evidence(Path("artifacts/logging-full-before-server"))),
                  native=native, regression=regression, nativeChecks=149, auditBinaries=binaries,
                  packages=previous["packages"], matchingNativePackages=packages, reusedOrdinaryTestsPerTarget=828, reusedInteractionsTests=100,
                  reusedEvidence=evidence("docs/craft-index-validation-2026-10-23.json"), unchangedPinnedClient=client,
                  stopped=stopped, stoppedEvidence=evidence("artifacts/logging-full-stopped.json"), stoppedVerifier=evidence("artifacts/confirm-logging-full-stopped.ps1"),
                  guards=guards, guardEvidence=evidence("artifacts/logging-full-launch-rejections.json"), guardVerifier=evidence("artifacts/check-logging-full-guards.ps1"),
                  sources=[evidence(p) for p in sources + [patch, "tools/record-logging-full-validation.py"]],
                  runner=evidence("artifacts/run-logging-full-audit.ps1"),
                  limits=["Actual SQLITE_FULL from bounded page caps; physical device loss, IOERR and COMMIT-FULL not exercised",
                          "Recovery needs native rollback proof before commit and restored DEFERRED transaction; uncertain outcomes remain fenced",
                          "Native separate-actor capacity padding is retained and counted, never attributed to gameplay actor",
                          "Pending queues remain in memory; unflushed termination, administration, retention/load and MySQL remain open",
                          "Independent world/log/financial saves are not atomic; migration and deferred physical clients remain open",
                          "No production or pinned client activation"])
    (ROOT / "docs/logging-full-recovery-validation-2026-10-23.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("PASS 93 actual-bytecode SQLite and 149 native/restart assertions; retained original records and packages")


if __name__ == "__main__":
    validate()
