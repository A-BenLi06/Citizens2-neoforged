"""Verify provider coordinate indexes, original rows, configuration and native regressions."""
from datetime import datetime
from pathlib import Path
import hashlib
import importlib.util
import io
import json
import sqlite3
import subprocess
import sys
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
HISTORY = ["blocks", "chats", "commands", "containers", "items", "sessions"]
TABLES = ["users", "usernames", "levels", "materials", "entities", *HISTORY]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


base = module("logging_base", "tools/record-logging-full-validation.py")
shutdown = module("logging_shutdown", "tools/record-logging-shutdown-validation.py")
read, raw, evidence, files, git = base.read, base.raw, base.evidence, base.files, base.git


def rows(db, table):
    return [[{"blob": value.hex()} if isinstance(value, bytes) else value for value in row]
            for row in db.execute("SELECT " + ("rowid,time,user,level,x,y,z,type,action" if table == "blocks" else "rowid,*") + " FROM " + table + " ORDER BY rowid")]


def snapshot(path):
    path = (ROOT / path).resolve()
    assert not any(Path(str(path) + suffix).exists() for suffix in ["-wal", "-shm", "-journal"])
    with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
        assert db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
        return {table: rows(db, table) for table in TABLES}


def verify_workload(state, original):
    tables = state["tables"]
    assert state["rowsPerTable"] == 2048
    assert tables["users"][:len(original["users"])] == original["users"]
    assert len(tables["users"]) == len(original["users"]) + 1
    assert tables["users"][-1][1:] == [state["user"], "IndexLoad", state["actor"]]
    for table in ["usernames", "levels", "materials", "entities"]:
        assert tables[table] == original[table]
    material = next(row[1] for row in original["materials"] if row[2] == "paper")
    level = next(row[1] for row in original["levels"] if row[2] == "minecraft:overworld")
    components = original["containers"][0][8]
    for table in HISTORY:
        assert tables[table][:len(original[table])] == original[table]
        added = tables[table][len(original[table]):]
        assert len(added) == 2048
        for index, row in enumerate(added):
            assert row[1] == added[0][1] + index and row[2:4] == [state["user"], level]
            assert row[4:7] == [7 if index in [0, 2047] else 100 + index, -60, 3]
            if table == "blocks":
                assert row[7:] == [material, 0]
            elif table in ["containers", "items"]:
                assert row[7:] == [material, components, 7, 0]
            elif table in ["chats", "commands"]:
                assert row[7:] == ["Index payload '\" " + str(index)]
            else:
                assert row[7:] == [index % 2]


def verify_queries(path, state, queries, enabled):
    result = {}
    with sqlite3.connect((ROOT / path).resolve().as_uri() + "?mode=ro&immutable=1", uri=True) as db:
        for table in HISTORY:
            is_enabled = enabled[table] if isinstance(enabled, dict) else enabled
            query = queries[table]
            projection = "rowid,time,user,level,x,y,z,type,action" if table == "blocks" else "rowid,*"
            assert query["sql"] == f"SELECT {projection} FROM {table} WHERE user=? AND x=? AND y=? AND z=? ORDER BY time DESC"
            expected = sorted([row for row in state["tables"][table]
                               if row[2] == state["user"] and row[4:7] == [7, -60, 3]], key=lambda row: row[1], reverse=True)
            assert len(expected) == 2 and query["rows"] == expected
            parameters = (state["user"], 7, -60, 3)
            actual = [[{"blob": value.hex()} if isinstance(value, bytes) else value for value in row]
                      for row in db.execute(query["sql"], parameters)]
            assert actual == expected
            plan = [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + query["sql"], parameters)]
            assert plan == query["plan"]
            search = any(f"SEARCH {table} USING INDEX " in step for step in plan)
            scan = any(step.startswith("SCAN " + table) for step in plan)
            assert (search and not scan) if is_enabled else (scan and not search)
            indexes = []
            for item in db.execute(f"PRAGMA index_list('{table}')"):
                columns = [row[2] for row in db.execute(f"PRAGMA index_info('{item[1]}')")]
                if columns == ["x", "y", "z"]:
                    indexes.append(item[1])
            assert indexes == (["coordinates" if table == "blocks" else table + "_coordinates"] if is_enabled else [])
            result[table] = dict(indexes=indexes, plan=plan, rows=actual)
    return result


def validate():
    provider = "artifacts/grieflogger-index-source/neoforge/build/libs/grieflogger-1.2.10-1.21.1-yuuniverse.7-neoforge.jar"
    source = ROOT / "artifacts/grieflogger-index-source"
    parent = ROOT / "artifacts/grieflogger-shutdown-item-source"
    patch = "tools/grieflogger-compat/sqlite-coordinate-indexes.patch"
    assert not git("status", "--porcelain", cwd=source)
    assert git("log", "-1", "--format=%an <%ae>|%cn <%ce>", cwd=source) == "A-BenLi06 <ben_li06@outlook.com>|A-BenLi06 <ben_li06@outlook.com>"
    subprocess.run(["git", "apply", "--check", str(ROOT / patch)], cwd=parent, check=True)
    subprocess.run(["git", "apply", "--reverse", "--check", str(ROOT / patch)], cwd=source, check=True)
    assert "BUILD SUCCESSFUL" in raw("artifacts/grieflogger-index-provider-build.log")
    previous = read("docs/logging-shutdown-validation-2026-10-23.json")
    assert evidence(previous["provider"]["path"]) == previous["provider"]
    drivers = []
    for path in [previous["provider"]["path"], provider]:
        with zipfile.ZipFile(ROOT / path) as jar:
            assert not any(name.startswith("org/sqlite/") for name in jar.namelist())
            entries = [entry for entry in json.loads(jar.read("META-INF/jarjar/metadata.json"))["jars"]
                       if entry["identifier"] == dict(group="org.xerial", artifact="sqlite-jdbc")]
            assert len(entries) == 1
            with zipfile.ZipFile(io.BytesIO(jar.read(entries[0]["path"]))) as driver:
                drivers.append({name: driver.read(name) for name in driver.namelist() if not name.endswith("/")})
    assert drivers[0] == drivers[1]
    standalone = read("artifacts/grieflogger-index-final/validation.json")
    assert standalone["checks"] == 109 and standalone["actualProviderBytecode"]
    for item in standalone["files"]:
        assert hashlib.sha256(Path(item["path"]).read_bytes()).hexdigest() == item["sha256"]
    standalone_dbs = [shutdown.database(path) for path in sorted((ROOT / "artifacts/grieflogger-index-final/databases").glob("*.db"))]
    assert len(standalone_dbs) == 24
    original_fixture = Path("artifacts/logging-shutdown-normal-regression-server")
    original = snapshot(original_fixture / "database.db")
    original_financial = read(original_fixture / "logging-restart-state.json")
    records = []
    for prefix, modes, counts in [
            ("logging-index-before", ["indexes"], []),
            ("logging-index-final", ["indexes", "indexes-recover"], [26, 25]),
            ("logging-index-disabled", ["indexes-disabled", "indexes-recover"], [24, 25])]:
        fixture = Path("artifacts") / (prefix + "-server")
        assert read(fixture / "logging-index-original.json") == original
        state = read(fixture / "logging-index-state.json")
        verify_workload(state, original)
        assert snapshot(fixture / "database.db") == state["tables"]
        record = dict(state=evidence(fixture / "logging-index-state.json"), original=evidence(fixture / "logging-index-original.json"),
                      database=shutdown.database(fixture / "database.db"), rowsPerTable=2048, totalNewHistoryRows=12288,
                      originalRows={table: len(value) for table, value in original.items()}, phases=[])
        for index, phase in enumerate(modes):
            log = f"artifacts/{prefix}-{phase}.log"
            text = raw(log)
            assert "NeoForge 21.1.248" in text and "not committed" not in text and "Failed to drain database writes after server stop" not in text
            if prefix == "logging-index-before":
                assert "GriefLogger 1.2.10-1.21.1-yuuniverse.6" in text
                assert "AssertionError: every_history_table_owns_coordinate_index_chats" in text and "[LOGGINGINDEX] COMPLETE" not in text
                assert state["initialIndexes"] == {table: ["coordinates"] if table == "blocks" else [] for table in HISTORY}
                record["queries"] = verify_queries(fixture / "database.db", state, state["initialQueries"], {table: table == "blocks" for table in HISTORY})
                for table in HISTORY:
                    plan = state["initialQueries"][table]["plan"]
                    assert any((f"SEARCH {table} USING INDEX " in step) if table == "blocks" else step.startswith("SCAN " + table) for step in plan)
                record["reproducedMissingIndexes"] = 5
            else:
                assert "GriefLogger 1.2.10-1.21.1-yuuniverse.7" in text
                assert f"[LOGGINGINDEX] COMPLETE {counts[index]} checks phase={phase}" in text and "BUILD SUCCESSFUL" in text
                assert "[LOGGINGAUDIT] FAILED" not in text
                result = read(fixture / ("logging-index-recovery.json" if index else "logging-index-result.json"))
                assert result["checks"] == counts[index] and result["phase"] == phase
                enabled = phase != "indexes-disabled"
                stopped_db = f"artifacts/{prefix}-stopped-{phase}.db"
                assert snapshot(stopped_db) == state["tables"]
                query_evidence = verify_queries(stopped_db, state, result["queries"], enabled)
                assert result["indexes"] == {table: value["indexes"] for table, value in query_evidence.items()}
                record["phases"].append(dict(phase=phase, checks=counts[index], result=evidence(fixture / ("logging-index-recovery.json" if index else "logging-index-result.json")),
                                             database=shutdown.database(stopped_db), queries=query_evidence))
            record.setdefault("logs", []).append(evidence(log))
        record["financial"] = base.database_evidence(fixture, original_financial, True)[1]
        for table in original:
            assert state["tables"][table][:len(original[table])] == original[table]
        if prefix != "logging-index-before":
            assert evidence(fixture / "mods" / Path(provider).name)["sha256"] == evidence(provider)["sha256"]
        records.append(record)
    normal_fixture = Path("artifacts/logging-index-normal-regression-server")
    normal_state = read(normal_fixture / "logging-restart-state.json")
    normal_logs = []
    for phase, marker in [("native", "37 checks"), ("recover", "8 restart checks")]:
        path = f"artifacts/logging-index-normal-regression-{phase}.log"
        text = raw(path)
        assert "[LOGGINGAUDIT] COMPLETE " + marker in text and "BUILD SUCCESSFUL" in text and "[LOGGINGAUDIT] FAILED" not in text
        assert "GriefLogger 1.2.10-1.21.1-yuuniverse.7" in text and "not committed" not in text and "Failed to drain database writes after server stop" not in text
        normal_logs.append(evidence(path))
    normal = dict(logs=normal_logs, state=normal_state, databases=base.database_evidence(normal_fixture, normal_state, True))
    stop_fixture = Path("artifacts/logging-index-shutdown-regression-server")
    stop_state = read(stop_fixture / "logging-shutdown-result.json")
    recovery = read(stop_fixture / "logging-shutdown-recovery.json")
    assert stop_state["checks"] == 11 and recovery["recoveryChecks"] == 8 and all(recovery[key] == stop_state[key] for key in stop_state)
    stop = dict(state=stop_state, recovered=recovery, database=shutdown.database(stop_fixture / "database.db", stop_state, {0: 2, 1: 2}),
                nativeStorage=shutdown.native_storage(stop_fixture / "logging-world", stop_state), phases=[])
    for phase, count, sessions in [("shutdown", 11, {0: 1, 1: 1}), ("shutdown-recover", 8, {0: 2, 1: 2})]:
        log = f"artifacts/logging-index-shutdown-regression-{phase}.log"
        text = raw(log)
        assert f"[LOGGINGSHUTDOWN] COMPLETE {count} checks phase={phase}" in text and "BUILD SUCCESSFUL" in text
        assert "[LOGGINGAUDIT] FAILED" not in text and "not committed" not in text and "Failed to drain database writes after server stop" not in text
        prefix = f"artifacts/logging-index-shutdown-regression-stopped-{phase}"
        stop["phases"].append(dict(log=evidence(log), database=shutdown.database(prefix + ".db", stop_state, sessions),
                                   nativeStorage=shutdown.native_storage(Path(prefix + "-world"), stop_state)))
    builds = ["logging-index-before", "logging-index-final", "logging-index-disabled", "logging-index-normal-regression", "logging-index-shutdown-regression"]
    binaries = [evidence(f"artifacts/{build}-build/classes/java/dialogue/net/yuuniverse/interactions/LoggingIndexRuntimeAudit.class") for build in builds]
    assert len({item["sha256"] for item in binaries}) == 1
    assert not git("diff", "aa68330", "--", "neoforge/src/main", "neoforge/src/dialogue", "neoforge/src/test", "neoforge/build.gradle", "tools/audit-tests", "tools/audit-tests.gradle")
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
    stopped = read("artifacts/logging-index-stopped.json")
    assert len(stopped["fixtures"]) == 7 and len(stopped["snapshots"]) == 2
    for record in stopped["fixtures"] + stopped["snapshots"]:
        assert record["exclusive"]
        for item in record["files"]:
            assert evidence(item["path"]) == item
    guards = read("artifacts/logging-index-launch-rejections.json")
    assert guards["before"] == guards["after"] and len(guards["checks"]) == 4
    for path, digest in guards["before"].items():
        assert evidence(path)["sha256"].upper() == digest
    for check in guards["checks"]:
        assert check["exitCode"] != 0 and check["expected"] in raw(check["log"]) and "Starting minecraft server" not in raw(check["log"])
    preparation = read("artifacts/logging-index-preparation-rejections.json")
    assert preparation["outsidePathAbsent"] and len(preparation["checks"]) == 3
    for check in preparation["checks"]:
        assert check["expected"] in check["message"]
    assert not Path(preparation["checks"][1]["path"]).exists()
    assert not Path(preparation["checks"][2]["path"]).exists()
    creation_source = "artifacts/logging-index-references/prepare-at-fixture-creation.ps1"
    overlap_guard = ("if($fixture.StartsWith($source+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {\n"
                     "    throw 'Index fixture must not be inside its source fixture'\n}\n")
    assert raw("tools/prepare-logging-index-audit.ps1").count(overlap_guard) == 1
    assert raw("tools/prepare-logging-index-audit.ps1").replace(overlap_guard, "") == raw(creation_source)
    sources = ["tools/logging-runtime-audit.gradle", "tools/prepare-logging-index-audit.ps1", "tools/grieflogger-compat/run-storage-tests.ps1"]
    sources += [item["path"] for item in files("tools/logging-runtime-audit") + files("tools/grieflogger-compat/storage-tests") if item["path"].endswith(".java")]
    for path in sources:
        assert (ROOT / path).read_bytes() == (ROOT / "artifacts/logging-index-references" / Path(path).relative_to("tools")).read_bytes()
    result = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", baseRevision="aa68330",
                  provider=evidence(provider), providerCommit=git("rev-parse", "HEAD", cwd=source), patch=evidence(patch),
                  providerSources=[evidence(source / path) for path in git("diff", "--name-only", "f35314d", "HEAD", cwd=source).splitlines()],
                  providerBuild=evidence("artifacts/grieflogger-index-provider-build.log"), unchangedDriverFiles=len(drivers[1]),
                  standalone=standalone, standaloneDatabases=standalone_dbs, indexAcceptance=records, normal=normal, shutdown=stop, nativeChecks=164,
                  sources=[evidence(path) for path in sources + ["tools/record-logging-index-validation.py", "tools/record-logging-shutdown-validation.py", "tools/record-logging-full-validation.py"]],
                  runner=evidence("artifacts/run-logging-index-audit.ps1"), matrixRunner=evidence("artifacts/run-logging-index-matrix.ps1"),
                  auditBinaries=binaries, packages=previous["packages"], reusedOrdinaryPerTarget=828, reusedInteractions=100,
                  unchangedPinnedClient=previous["unchangedPinnedClient"], stopped=stopped, guards=guards, preparation=preparation,
                  stoppedVerifier=evidence("artifacts/confirm-logging-index-stopped.ps1"), guardVerifier=evidence("artifacts/check-logging-index-guards.ps1"),
                  preparationVerifier=evidence("artifacts/check-logging-index-preparation.ps1"),
                  fixtureCreationSource=evidence(creation_source),
                  limits=["Bounded fixture SQL workload and coordinate plans; no production service-rate claim",
                          "Exact coordinate queries match the provider index contract; radius sorting and public lookup limits remain",
                          "Workload rows have their own synthetic identity; they are not native gameplay observations",
                          "No history expiry, dictionary pruning, retention tooling or crash spool added",
                          "MySQL behavior unchanged; MySQL acceptance, retention/load, wider recovery, migration and deferred clients remain",
                          "No production or deferred client activation"])
    (ROOT / "docs/logging-index-validation-2026-10-23.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("PASS 109 SQLite + 164 native/restart checks; six coordinate plans and exact retained rows")


if __name__ == "__main__":
    validate()
