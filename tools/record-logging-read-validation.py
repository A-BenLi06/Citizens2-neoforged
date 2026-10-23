"""Independently verify transaction-isolated provider lookups and retained native state."""
from datetime import datetime
from pathlib import Path
import hashlib
import importlib.util
import io
import json
import re
import sqlite3
import subprocess
import sys
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


base = module("logging_read_base", "tools/record-logging-full-validation.py")
shutdown = module("logging_read_shutdown", "tools/record-logging-shutdown-validation.py")
indexes = module("logging_read_indexes", "tools/record-logging-index-validation.py")
read, raw, evidence, git = base.read, base.raw, base.evidence, base.git


def accepted_log(name, phase, marker):
    path = f"artifacts/{name}-{phase}.log"
    text = raw(path)
    assert marker in text and "BUILD SUCCESSFUL" in text
    assert "[LOGGINGAUDIT] FAILED" not in text and "Failed to drain database writes after server stop" not in text
    assert "not committed" not in text and "GriefLogger 1.2.10-1.21.1-yuuniverse.8" in text
    return evidence(path)


def validate():
    source = ROOT / "artifacts/grieflogger-read-source"
    parent = ROOT / "artifacts/grieflogger-index-source"
    provider = "artifacts/grieflogger-read-source/neoforge/build/libs/grieflogger-1.2.10-1.21.1-yuuniverse.8-neoforge.jar"
    patch = "tools/grieflogger-compat/repository-read-isolation.patch"
    assert not git("status", "--porcelain", cwd=source)
    assert git("log", "-1", "--format=%an <%ae>|%cn <%ce>", cwd=source) == "A-BenLi06 <ben_li06@outlook.com>|A-BenLi06 <ben_li06@outlook.com>"
    subprocess.run(["git", "apply", "--check", str(ROOT / patch)], cwd=parent, check=True)
    subprocess.run(["git", "apply", "--reverse", "--check", str(ROOT / patch)], cwd=source, check=True)
    assert "BUILD SUCCESSFUL" in raw("artifacts/grieflogger-read-provider-build.log")
    previous = read("docs/logging-index-validation-2026-10-23.json")
    assert evidence(previous["provider"]["path"]) == previous["provider"]
    drivers = []
    for path in [previous["provider"]["path"], provider]:
        with zipfile.ZipFile(ROOT / path) as archive:
            assert not any(name.startswith("org/sqlite/") for name in archive.namelist())
            metadata = json.loads(archive.read("META-INF/jarjar/metadata.json"))
            driver = next(item for item in metadata["jars"] if item["identifier"] == dict(group="org.xerial", artifact="sqlite-jdbc"))
            with zipfile.ZipFile(io.BytesIO(archive.read(driver["path"]))) as nested:
                drivers.append({name: hashlib.sha256(nested.read(name)).hexdigest() for name in nested.namelist() if not name.endswith("/")})
    assert drivers[0] == drivers[1] and len(drivers[1]) == 163
    repo = Path("common/src/main/java/com/daqem/grieflogger/database/repository")
    for name in ["Block", "Container", "Item", "Session", "User"]:
        before = (parent / repo / f"{name}Repository.java").read_text()
        after = (source / repo / f"{name}Repository.java").read_text()
        assert re.findall(r'"""(.*?)"""', before, re.S) == re.findall(r'"""(.*?)"""', after, re.S)
        assert "database.prepareStatement(query)" not in after
    standalone = read("artifacts/grieflogger-read-final/validation.json")
    assert standalone["checks"] == 143 and standalone["actualProviderBytecode"]
    for item in standalone["files"]:
        data = Path(item["path"]).read_bytes()
        assert (len(data), hashlib.sha256(data).hexdigest()) == (item["bytes"], item["sha256"])
    databases = []
    for path in sorted((ROOT / "artifacts/grieflogger-read-final/databases").glob("*.db")):
        with sqlite3.connect(path.as_uri()+"?mode=ro&immutable=1", uri=True) as db:
            assert db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
        databases.append(dict(**evidence(path), quickCheck="ok"))
    assert len(databases) == 28
    reproduction = "artifacts/grieflogger-read-before-final/diagnostic.log"
    assert "DURING public_lookup={1=uncommitted} independent_committed_rows=0" in raw(reproduction)
    assert "AFTER_ROLLBACK public_lookup={}" in raw(reproduction) and "REPRODUCED public repository returned a row that never committed" in raw(reproduction)
    fixture = Path("artifacts/logging-read-accepted-server")
    state = read(fixture / "logging-read-state.json")
    recovery = read(fixture / "logging-read-recovery.json")
    original = read(fixture / "logging-index-original.json")
    assert indexes.snapshot(fixture / "database.db") == state["tables"]
    assert recovery["lookups"] == state["lookups"] and len(state["lookups"]) == 9
    assert state["checks"] == 61 and recovery["checks"] == 11
    tables = state["tables"]
    for table in indexes.TABLES:
        assert tables[table][:len(original[table])] == original[table]
        assert len(tables[table]) == len(original[table]) + ({"users": 1, "blocks": 3, "containers": 2, "items": 2, "sessions": 2}.get(table, 0))
    actor_id, actor_name, actor = tables["users"][-1][1:]
    assert actor_name == "ReadAudit"
    material = next(row[1] for row in original["materials"] if row[2] == "paper")
    level = next(row[1] for row in original["levels"] if row[2] == "minecraft:overworld")
    times = set()
    for table in ["blocks", "containers", "items", "sessions"]:
        for action, row in enumerate(tables[table][len(original[table]):]):
            assert row[2:7] == [actor_id, level, 29, -60, 29]
            times.add(row[1] - action)
            assert row[7:] == ([material, action] if table == "blocks" else [action] if table == "sessions" else [material, original["containers"][0][8], 7, action])
    assert len(times) == 1
    for key, rows in state["lookups"].items():
        if key == "usernames":
            assert rows == {str(row[1]): row[2] for row in tables["users"]}
            continue
        actions = [2] if key == "interactions" else [2, 1, 0] if key == "filteredBlocks" else [1, 0]
        assert [row["action"] for row in rows] == actions
        for row in rows:
            assert row["uuid"] == actor and row["name"] == actor_name and row["position"] == dict(x=29, y=-60, z=29)
            assert row["time"] == next(iter(times)) + row["action"]
            if "container" in key.lower() or key == "items":
                assert row["paper"] and row["amount"] == 7 and "Logging components" in row["patch"]
    lookup_logs = [accepted_log("logging-read-accepted", phase, f"[LOGGINGREAD] COMPLETE {count} checks phase={phase}")
                   for phase, count in [("reads", 61), ("reads-recover", 11)]]
    for phase in ["reads", "reads-recover"]:
        assert indexes.snapshot(f"artifacts/logging-read-accepted-stopped-{phase}.db") == state["tables"]
    normal_fixture = Path("artifacts/logging-read-normal-regression-server")
    normal_state = read(normal_fixture / "logging-restart-state.json")
    normal = dict(state=normal_state, databases=base.database_evidence(normal_fixture, normal_state, True),
                  logs=[accepted_log("logging-read-normal-regression", phase, "[LOGGINGAUDIT] COMPLETE " + marker)
                        for phase, marker in [("native", "37 checks"), ("recover", "8 restart checks")]])
    stop_fixture = Path("artifacts/logging-read-shutdown-regression-server")
    stop_state = read(stop_fixture / "logging-shutdown-result.json")
    stop_recovery = read(stop_fixture / "logging-shutdown-recovery.json")
    assert stop_state["checks"] == 11 and stop_recovery["recoveryChecks"] == 8
    assert all(stop_recovery[key] == stop_state[key] for key in stop_state)
    stop = dict(state=stop_state, recovered=stop_recovery, phases=[])
    for phase, count, sessions in [("shutdown", 11, {0: 1, 1: 1}), ("shutdown-recover", 8, {0: 2, 1: 2})]:
        prefix = f"artifacts/logging-read-shutdown-regression-stopped-{phase}"
        stop["phases"].append(dict(log=accepted_log("logging-read-shutdown-regression", phase, f"[LOGGINGSHUTDOWN] COMPLETE {count} checks phase={phase}"),
                                   database=shutdown.database(prefix+".db", stop_state, sessions), nativeStorage=shutdown.native_storage(Path(prefix+"-world"), stop_state)))
    index_fixture = Path("artifacts/logging-read-index-regression-server")
    index_state = read(index_fixture / "logging-index-state.json")
    index_original = read(index_fixture / "logging-index-original.json")
    indexes.verify_workload(index_state, index_original)
    assert indexes.snapshot(index_fixture / "database.db") == index_state["tables"]
    index_results = []
    for phase, file, count in [("indexes", "logging-index-result.json", 26), ("indexes-recover", "logging-index-recovery.json", 25)]:
        result = read(index_fixture / file)
        assert result["checks"] == count
        index_results.append(dict(log=accepted_log("logging-read-index-regression", phase, f"[LOGGINGINDEX] COMPLETE {count} checks phase={phase}"),
                                  queries=indexes.verify_queries(f"artifacts/logging-read-index-regression-stopped-{phase}.db", index_state, result["queries"], True)))
    builds = ["logging-read-accepted", "logging-read-normal-regression", "logging-read-shutdown-regression", "logging-read-index-regression"]
    installed = []
    for name in builds:
        selected = list((ROOT / f"artifacts/{name}-server/mods").glob("grieflogger-*.jar"))
        assert len(selected) == 1
        item = evidence(selected[0])
        assert item["sha256"] == evidence(provider)["sha256"]
        installed.append(item)
    binaries = [evidence(f"artifacts/{name}-build/classes/java/dialogue/net/yuuniverse/interactions/LoggingReadRuntimeAudit.class") for name in builds]
    assert len({item["sha256"] for item in binaries}) == 1
    assert not git("diff", "d70cc6c", "--", "neoforge/src/main", "neoforge/src/dialogue", "neoforge/src/test", "neoforge/build.gradle", "tools/audit-tests", "tools/audit-tests.gradle")
    for package in previous["packages"]:
        assert evidence(package["path"]) == {key: package[key] for key in ["path", "bytes", "sha256"]}
        if "21.1.248-build" in package["path"] and not package["path"].endswith("-sources.jar"):
            source_set = "dialogue" if Path(package["path"]).name.startswith("yuuniverse-interactions") else "main"
            with zipfile.ZipFile(ROOT / package["path"]) as archive:
                for name in [name for name in archive.namelist() if name.endswith(".class")]:
                    for build in builds:
                        assert archive.read(name) == (ROOT / f"artifacts/{build}-build/classes/java/{source_set}" / name).read_bytes()
    ordinary = read("docs/craft-index-validation-2026-10-23.json")
    for test in [*ordinary["ordinary"], ordinary["interactions"]]:
        assert test["results"] == dict(tests=100 if test is ordinary["interactions"] else 828, failures=0, errors=0, skipped=0)
        for item in [test["log"], *test["reports"]]:
            assert evidence(item["path"]) == item
    for record in previous["stopped"]["fixtures"]:
        if record["fixture"] in ["logging-shutdown-normal-regression-server", "ordinary-client-acceptance-server"]:
            for item in record["files"]:
                assert evidence(item["path"]) == item
    for item in read(previous["unchangedPinnedClient"]["path"])["files"]:
        assert evidence(item["path"])["sha256"].upper() == item["sha256"]
    stopped = read("artifacts/logging-read-stopped.json")
    assert len(stopped["fixtures"]) == 7 and len(stopped["snapshots"]) == 2
    for record in stopped["fixtures"] + stopped["snapshots"]:
        assert record["exclusive"]
        for item in record["files"]:
            assert evidence(item["path"]) == item
    guards = read("artifacts/logging-read-launch-rejections.json")
    assert guards["before"] == guards["after"] and len(guards["checks"]) == 4
    for path, digest in guards["before"].items():
        assert evidence(path)["sha256"].upper() == digest
    for check in guards["checks"]:
        assert check["exitCode"] != 0 and check["expected"] in raw(check["log"]) and "Starting minecraft server" not in raw(check["log"])
    sources = ["tools/logging-runtime-audit.gradle", "tools/grieflogger-compat/run-storage-tests.ps1", "tools/prepare-logging-index-audit.ps1", "tools/record-logging-read-validation.py",
               "tools/record-logging-full-validation.py", "tools/record-logging-shutdown-validation.py", "tools/record-logging-index-validation.py"]
    sources += [str(path.relative_to(ROOT)).replace("\\", "/") for folder in ["tools/grieflogger-compat/storage-tests", "tools/logging-runtime-audit"] for path in (ROOT / folder).rglob("*.java")]
    for path in sources:
        assert (ROOT / path).read_bytes() == (ROOT / "artifacts/logging-read-references" / Path(path).relative_to("tools")).read_bytes()
    result = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", baseRevision="d70cc6c",
                  provider=evidence(provider), providerCommit=git("rev-parse", "HEAD", cwd=source), patch=evidence(patch), unchangedDriverFiles=163,
                  providerSources=[evidence(source / path) for path in git("diff", "--name-only", "8081e200", "HEAD", cwd=source).splitlines()],
                  providerBuild=evidence("artifacts/grieflogger-read-provider-build.log"), installedProviders=installed,
                  reproduction=[evidence(path) for path in [reproduction, "artifacts/grieflogger-read-before-final/ReadBefore.java", "artifacts/grieflogger-read-before-final/classes/ReadBefore.class", "artifacts/grieflogger-read-before-final/database.db"]],
                  retainedDiagnosticFailures=[evidence(path) for path in ["artifacts/grieflogger-read-before/diagnostic.log", "artifacts/logging-read-final-reads.log"]],
                  standalone=standalone, standaloneDatabases=databases, readAcceptance=dict(state=state, recovered=recovery, logs=lookup_logs),
                  normal=normal, shutdown=stop, indexes=index_results, nativeChecks=187,
                  sources=[evidence(path) for path in sources], auditBinaries=binaries, packages=previous["packages"],
                  reusedOrdinaryPerTarget=828, reusedInteractions=100, unchangedPinnedClient=previous["unchangedPinnedClient"], stopped=stopped, guards=guards,
                  runner=evidence("artifacts/run-logging-read-audit.ps1"), regressionRunner=evidence("artifacts/run-logging-read-regressions.ps1"),
                  stoppedVerifier=evidence("artifacts/confirm-logging-read-stopped.ps1"), guardVerifier=evidence("artifacts/check-logging-read-guards.ps1"),
                  limits=["Nine repository reads serialize through complete cursor consumption; external raw JDBC callers remain responsible for transaction ownership",
                          "Fixture-owned deterministic writes exercise real repository APIs and decoding, not a new native gameplay observation",
                          "Existing repository SQLException logging/empty-or-partial fallback contracts remain; uncertainty is not a successful read",
                          "Single shared connection: long queries and writes can delay each other; sustained load and MySQL acceptance remain open",
                          "No history expiry, crash spool, wider backup recovery, migration completion or deferred physical-client acceptance claimed",
                          "No production or deferred client activation"])
    (ROOT / "docs/logging-read-validation-2026-10-23.json").write_text(json.dumps(result, indent=2)+"\n", encoding="utf-8")
    print("PASS 143 actual SQLite and 187 native/restart checks; nine repository lookup paths preserve committed data")


if __name__ == "__main__":
    validate()
