"""Verify real MySQL command storage, migrations, persistence and SQLite regressions."""
from collections import Counter
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


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


base = module("mysql_base", "tools/record-logging-full-validation.py")
shutdown = module("mysql_shutdown", "tools/record-logging-shutdown-validation.py")
indexes = module("mysql_indexes", "tools/record-logging-index-validation.py")
read, raw, evidence, git = base.read, base.raw, base.evidence, base.git
PROVIDER = "artifacts/grieflogger-mysql-command-source/neoforge/build/libs/grieflogger-1.2.10-1.21.1-yuuniverse.9-neoforge.jar"
CASES = {"upgrade": "loggingaudit_after", "fresh": "loggingaudit_commands_fresh",
         "longtext": "loggingaudit_longtext", "text": "loggingaudit_text_upgrade"}


def log(name, phase, marker, rejected_schema=False):
    path = f"artifacts/{name}-{phase}.log"
    text = raw(path)
    assert marker in text and "BUILD SUCCESSFUL" in text, path
    assert "[LOGGINGAUDIT] FAILED" not in text and "NeoForge 21.1.248" in text, path
    assert "GriefLogger 1.2.10-1.21.1-yuuniverse.9" in text, path
    if rejected_schema:
        assert "Unsupported commands.command schema" in text
        assert "logging admission disabled" in text
    else:
        assert "logging admission disabled" not in text
        assert "Failed to drain database writes after server stop" not in text and "not committed" not in text
    return evidence(path)


def snapshot(database, phase="before-restart"):
    return read(f"artifacts/logging-mysql-{phase}/{database}/snapshot.json")


def command_rows(snapshot):
    tables = snapshot["tables"]
    users = {row[0]: row[1:] for row in tables["users"]["rows"]}
    levels = dict(tables["levels"]["rows"])
    result = []
    for time, user, level, x, y, z, command in tables["commands"]["rows"]:
        name, uuid = users[user]
        result.append(dict(time=time, uuid=uuid, name=name, level=levels[level], x=x, y=y, z=z, command=command))
    return result


def same_rows(left, right):
    encode = lambda row: json.dumps(row, sort_keys=True, ensure_ascii=False)
    assert Counter(map(encode, left)) == Counter(map(encode, right))


def command_cases():
    result = []
    original = read("artifacts/logging-mysql-input-before/snapshot.json")
    for case, database in CASES.items():
        name = "logging-mysql-commands-" + case
        fixture = Path("artifacts") / (name + "-server")
        state = read(fixture / "logging-mysql-command-state.json")
        recovery = read(fixture / "logging-mysql-command-recovery.json")
        assert state["checks"] == 13 and recovery["checks"] == 5 and state["pending"] == 0
        assert recovery["column"] == state["column"]
        assert [len(value.encode("utf-8")) for value in state["inputs"]] == [23, 1032, 69025]
        assert all(len(value.encode("utf-16-le")) // 2 <= 32767 for value in state["inputs"])
        assert [row["command"] for row in state["commands"]] == state["inputs"]
        assert len(state["commands"]) == 3
        for row in state["commands"]:
            assert row["uuid"] == state["actor"] and row["name"] == "MysqlCommand"
            # Native connection setup settles this synthetic player onto Y=-61.
            assert row["level"] == "minecraft:overworld" and [row[key] for key in ["x", "y", "z"]] == [4, -61, 4]
        same_rows(state["originalCommands"], [row for row in state["allCommands"] if row["uuid"] != state["actor"]])
        actual = snapshot(database)
        same_rows(command_rows(actual), state["allCommands"])
        column = state["column"]
        assert column["DATA_TYPE"] == ("longtext" if case == "longtext" else "mediumtext")
        assert column["CHARACTER_SET_NAME"] == "utf8mb4" and column["IS_NULLABLE"] == "NO"
        if case != "fresh":
            same_rows(state["originalCommands"], command_rows(original))
            assert column["COLLATION_NAME"] == "utf8mb4_bin"
            before = read(f"artifacts/logging-mysql-input-{case}/snapshot.json") if case in ["longtext", "text"] else original
            for table, saved in before["tables"].items():
                after = actual["tables"][table]
                for row in saved["rows"]:
                    assert row in after["rows"], (case, table)
                for old_column, new_column in zip(saved["columns"], after["columns"], strict=True):
                    if table == "commands" and old_column["name"] == "command":
                        if case == "longtext":
                            assert old_column == new_column
                        else:
                            assert old_column["type"] in ["text", "varchar"]
                            assert {key: value for key, value in old_column.items() if key not in ["type", "columnType", "collation"]} == {
                                key: value for key, value in new_column.items() if key not in ["type", "columnType", "collation"]}
                    else:
                        assert old_column == new_column
        if case == "longtext":
            assert column["COLUMN_COMMENT"] == "fixture larger text column"
        logs = [log(name, phase, f"[MYSQLCOMMANDAUDIT] COMPLETE {count} checks phase={phase}")
                for phase, count in [("mysql-commands", 13), ("mysql-commands-recover", 5)]]
        result.append(dict(case=case, database=database, state=evidence(fixture / "logging-mysql-command-state.json"),
                           recovery=evidence(fixture / "logging-mysql-command-recovery.json"), logs=logs,
                           column=column, commandUtf8Bytes=[23, 1032, 69025], priorCommands=len(state["originalCommands"])))
    rejected = snapshot("loggingaudit_rejected")
    before = read("artifacts/logging-mysql-input-rejected/snapshot.json")
    assert before["tables"] == rejected["tables"] and before["indices"] == rejected["indices"]
    rejection = read("artifacts/logging-mysql-schema-reject-server/logging-mysql-schema-rejection.json")
    assert rejection["checks"] == 4
    same_rows(command_rows(rejected), rejection["allCommands"])
    rejection_log = log("logging-mysql-schema-reject", "mysql-schema-reject", "[MYSQLCOMMANDAUDIT] COMPLETE 4 checks phase=mysql-schema-reject", True)
    name = "logging-mysql-commands-before-final"
    failed = raw(f"artifacts/{name}-mysql-commands.log")
    assert "AssertionError: mysql_preserves_complete_native_command_1" in failed and "BUILD FAILED" in failed
    observed = read(f"artifacts/{name}-server/logging-mysql-command-observed.json")
    assert observed["pending"] == 0 and [len(row["command"]) for row in observed["commands"]] == [23, 256, 256]
    assert observed["commands"][1]["command"] != observed["inputs"][1]
    same_rows(command_rows(snapshot("loggingaudit_commands_before_final")), observed["allCommands"])
    return dict(accepted=result, rejectedSchema=dict(log=rejection_log, state=evidence("artifacts/logging-mysql-schema-reject-server/logging-mysql-schema-rejection.json")),
                reproduction=dict(log=evidence(f"artifacts/{name}-mysql-commands.log"), observed=evidence(f"artifacts/{name}-server/logging-mysql-command-observed.json"), silentlyTruncatedCharacters=[23, 256, 256]))


def sqlite_regressions():
    normal_name = "logging-mysql-sqlite-normal"
    fixture = Path("artifacts") / (normal_name + "-server")
    normal_state = read(fixture / "logging-restart-state.json")
    normal = dict(databases=base.database_evidence(fixture, normal_state, True),
                  logs=[log(normal_name, phase, "[LOGGINGAUDIT] COMPLETE " + marker) for phase, marker in [("native", "37 checks"), ("recover", "8 restart checks")]])
    stop_name = "logging-mysql-sqlite-shutdown"
    stop_fixture = Path("artifacts") / (stop_name + "-server")
    state = read(stop_fixture / "logging-shutdown-result.json")
    recovery = read(stop_fixture / "logging-shutdown-recovery.json")
    assert state["checks"] == 11 and recovery["recoveryChecks"] == 8 and all(recovery[key] == state[key] for key in state)
    stop = []
    for phase, count, sessions in [("shutdown", 11, {0: 1, 1: 1}), ("shutdown-recover", 8, {0: 2, 1: 2})]:
        prefix = f"artifacts/{stop_name}-stopped-{phase}"
        stop.append(dict(log=log(stop_name, phase, f"[LOGGINGSHUTDOWN] COMPLETE {count} checks phase={phase}"),
                         database=shutdown.database(prefix + ".db", state, sessions), nativeStorage=shutdown.native_storage(Path(prefix + "-world"), state)))
    index_name = "logging-mysql-sqlite-index"
    fixture = Path("artifacts") / (index_name + "-server")
    state = read(fixture / "logging-index-state.json")
    indexes.verify_workload(state, read(fixture / "logging-index-original.json"))
    assert indexes.snapshot(fixture / "database.db") == state["tables"]
    index_results = []
    for phase, file, count in [("indexes", "logging-index-result.json", 26), ("indexes-recover", "logging-index-recovery.json", 25)]:
        result = read(fixture / file)
        assert result["checks"] == count
        index_results.append(dict(log=log(index_name, phase, f"[LOGGINGINDEX] COMPLETE {count} checks phase={phase}"),
                                  queries=indexes.verify_queries(f"artifacts/{index_name}-stopped-{phase}.db", state, result["queries"], True)))
    read_name = "logging-mysql-sqlite-read"
    fixture = Path("artifacts") / (read_name + "-server")
    state, recovery = [read(fixture / file) for file in ["logging-read-state.json", "logging-read-recovery.json"]]
    assert state["checks"] == 61 and recovery["checks"] == 11
    assert state["lookups"] == recovery["lookups"] and len(state["lookups"]) == 9
    assert indexes.snapshot(fixture / "database.db") == state["tables"]
    original = read(fixture / "logging-index-original.json")
    for table in indexes.TABLES:
        assert state["tables"][table][:len(original[table])] == original[table]
    for phase in ["reads", "reads-recover"]:
        assert indexes.snapshot(f"artifacts/{read_name}-stopped-{phase}.db") == state["tables"]
    read_logs = [log(read_name, phase, f"[LOGGINGREAD] COMPLETE {count} checks phase={phase}") for phase, count in [("reads", 61), ("reads-recover", 11)]]
    return dict(normal=normal, shutdown=stop, indexes=index_results, reads=dict(logs=read_logs, state=evidence(fixture / "logging-read-state.json")), checks=187)


def validate():
    source = ROOT / "artifacts/grieflogger-mysql-command-source"
    parent = ROOT / "artifacts/grieflogger-read-source"
    patch = "tools/grieflogger-compat/mysql-command-capacity.patch"
    assert not git("status", "--porcelain", cwd=source)
    assert git("log", "-1", "--format=%an <%ae>|%cn <%ce>", cwd=source) == "A-BenLi06 <ben_li06@outlook.com>|A-BenLi06 <ben_li06@outlook.com>"
    for cwd, reverse in [(parent, []), (source, ["--reverse"])]:
        subprocess.run(["git", "apply", *reverse, "--check", str(ROOT / patch)], cwd=cwd, check=True)
    previous = read("docs/logging-read-validation-2026-10-23.json")
    assert evidence(previous["provider"]["path"]) == previous["provider"]
    drivers = []
    for provider in [previous["provider"]["path"], PROVIDER]:
        with zipfile.ZipFile(ROOT / provider) as archive:
            assert not any(name.startswith("org/sqlite/") for name in archive.namelist())
            entry = next(item for item in json.loads(archive.read("META-INF/jarjar/metadata.json"))["jars"] if item["identifier"] == dict(group="org.xerial", artifact="sqlite-jdbc"))
            with zipfile.ZipFile(io.BytesIO(archive.read(entry["path"]))) as nested:
                drivers.append({name: hashlib.sha256(nested.read(name)).hexdigest() for name in nested.namelist() if not name.endswith("/")})
    assert drivers[0] == drivers[1] and len(drivers[1]) == 163
    assert "BUILD SUCCESSFUL" in raw("artifacts/grieflogger-mysql-command-provider-build.log")
    standalone = read("artifacts/grieflogger-mysql-command-storage/validation.json")
    assert standalone["checks"] == 156 and standalone["actualProviderBytecode"]
    for item in standalone["files"]:
        path = Path(item["path"])
        assert len(path.read_bytes()) == item["bytes"] and hashlib.sha256(path.read_bytes()).hexdigest() == item["sha256"]
    databases = sorted((ROOT / "artifacts/grieflogger-mysql-command-storage/databases").glob("*.db"))
    assert len(databases) == 32
    for path in databases:
        with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
            assert db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
    storage_log = raw("artifacts/grieflogger-mysql-command-storage/mysql-tests.log")
    assert "[MYSQLSTORAGE] COMPLETE 38 checks" in storage_log
    assert "SERVER 8.4.13 DRIVER mysql-connector-j-8.4.0" in storage_log
    stored = snapshot("loggingaudit_storage")["tables"]
    for table in ["observations_ordinary_lock", "observations_batch_lock"]:
        same_rows(stored[table]["rows"], [[1, "updated", None], [2, "retained 汉🙂", "010203"]])
    same_rows(stored["observations_constraint"]["rows"], [[1, None, "04"], [2, "second", "05"]])
    same_rows(stored["observations_uncertain"]["rows"], [[1, "actually committed", None]])
    same_rows(stored["users"]["rows"], [[1, "committed 汉", None]])
    mysql = command_cases()
    sqlite = sqlite_regressions()
    normal_logs = [log("logging-mysql-normal-regression", phase, "[LOGGINGAUDIT] COMPLETE " + marker) for phase, marker in [("native", "37 checks"), ("recover", "8 restart checks")]]
    normal_fixture = Path("artifacts/logging-mysql-normal-regression-server")
    # The logging database is MySQL; validate the independent Economy ledger directly.
    state = read(normal_fixture / "logging-restart-state.json")
    native_tables = snapshot("loggingaudit_native_after")["tables"]
    assert native_tables["users"]["rows"] == [[1, "LoggingActor", state["actor"]]]
    materials = dict(native_tables["materials"]["rows"])
    entities = dict(native_tables["entities"]["rows"])
    assert set(materials.values()) == {"stone", "paper", "create:shaft", "oak_door", "chest"}
    history = native_tables["blocks"]["rows"]
    assert len(history) == 199
    expected = Counter({(13, -60, 7, "stone", 1): 96, (13, -60, 7, "stone", 0): 96,
                        (3, -60, 3, "stone", 1): 1, (5, -60, 3, "create:shaft", 1): 1,
                        (11, -60, 3, "oak_door", 1): 1, (7, -60, 3, "chest", 1): 1,
                        (7, -60, 3, "chest", 2): 1, (4, -60, 6, "pig", 3): 1, (9, -60, 3, "chest", 1): 1})
    assert Counter((*row[3:6], (entities if row[7] == 3 else materials)[row[6]], row[7]) for row in history) == expected
    assert all(row[1:3] == [1, 1] for row in history)
    container = native_tables["containers"]["rows"]
    assert len(container) == 1 and container[0][1:6] == [1, 1, 7, -60, 3]
    assert materials[container[0][6]] == "paper" and container[0][8:] == [7, 0]
    assert bytes.fromhex(container[0][7]) == bytes.fromhex("0100050800124C6F6767696E6720636F6D706F6E656E7473")
    assert Counter(row[6] for row in native_tables["sessions"]["rows"]) == {0: 2, 1: 2}
    with sqlite3.connect((ROOT / normal_fixture / "logging-world/yuuniverse_economy/economy.db").as_uri() + "?mode=ro&immutable=1", uri=True) as db:
        receipt = db.execute("SELECT transaction_id,shop_id,currency_id,player_account_id FROM commerce_operation WHERE operation_id=?", (state["operation"],)).fetchall()
        assert len(receipt) == 1 and receipt[0][:3] == (state["transaction"], state["shop"], state["currency"])
        assert db.execute("SELECT balance_minor_units FROM balance_projection WHERE account_id=? AND currency_id=?", (receipt[0][3], state["currency"])).fetchall() == [(state["balance"],)]
        assert not db.execute("SELECT transaction_id,currency_id,SUM(delta_minor_units) FROM ledger_entry GROUP BY transaction_id,currency_id HAVING SUM(delta_minor_units)<>0").fetchall()
    restart = read("artifacts/logging-mysql-container-restart.json")
    assert restart["sameContainer"] and restart["stopExitCode"] == 0 and restart["finalStopped"] and restart["finalPortReleased"]
    lifecycle = raw("artifacts/logging-mysql84-environment/server-lifecycle.log")
    assert lifecycle.count("Shutdown complete") >= 2
    assert len(restart["specialEntries"]) == 1 and restart["specialEntries"][0]["path"].endswith("/mysql.sock")
    for database in restart["databases"]:
        left, right = [snapshot(database, phase) for phase in ["before-restart", "after-restart"]]
        assert left["tables"] == right["tables"] and left["indices"] == right["indices"]
        assert left["server"] == right["server"] and left["server"]["version"] == "8.4.13"
        assert left["containerId"] == right["containerId"] == restart["containerId"]
        if set(indexes.HISTORY).issubset(left["tables"]):
            for table in indexes.HISTORY:
                assert [item["column"] for item in left["indices"] if item["table"] == table and item["name"] == "coordinates"] == ["x", "y", "z"]
    for item in restart["retainedData"]:
        assert evidence(item["path"]) == item
    guards = read("artifacts/logging-mysql-launch-rejections.json")
    assert len(guards["checks"]) == 6 and guards["before"] == guards["after"]
    for path, digest in guards["before"].items():
        assert evidence(path)["sha256"].upper() == digest
    for check in guards["checks"]:
        assert check["exitCode"] != 0 and check["expected"] in raw(check["log"]) and "Starting minecraft server" not in raw(check["log"])
    stopped = read("artifacts/logging-mysql-stopped.json")
    required_fixtures = {"logging-mysql-commands-" + case + "-server" for case in CASES}
    required_fixtures |= {"logging-mysql-sqlite-" + case + "-server" for case in ["normal", "shutdown", "index", "read"]}
    required_fixtures |= {"logging-mysql-schema-reject-server", "logging-mysql-normal-regression-server", "logging-mysql-commands-before-final-server", "ordinary-client-acceptance-server", "logging-shutdown-normal-regression-server"}
    assert required_fixtures.issubset({record["fixture"] for record in stopped["fixtures"]})
    assert len(stopped["snapshots"]) == 2
    for record in stopped["fixtures"] + stopped["snapshots"]:
        assert record["exclusive"]
        for item in record["files"]:
            assert evidence(item["path"]) == item
    builds = ["logging-mysql-commands-" + case for case in CASES] + ["logging-mysql-schema-reject", "logging-mysql-normal-regression"]
    builds += ["logging-mysql-sqlite-" + case for case in ["normal", "shutdown", "index", "read"]]
    installed = []
    for name in builds:
        jars = list((ROOT / f"artifacts/{name}-server/mods").glob("grieflogger-*.jar"))
        assert len(jars) == 1 and evidence(jars[0])["sha256"] == evidence(PROVIDER)["sha256"]
        installed.append(evidence(jars[0]))
    binaries = [evidence(f"artifacts/{name}-build/classes/java/dialogue/net/yuuniverse/interactions/LoggingMysqlCommandRuntimeAudit.class") for name in [*builds, "logging-mysql-commands-before-final"]]
    assert len({item["sha256"] for item in binaries}) == 1
    regression_binaries = []
    for kind in ["Read", "Shutdown", "Index"]:
        old = ROOT / "artifacts/logging-read-index-regression-build/classes/java/dialogue/net/yuuniverse/interactions"
        original_classes = {path.name: path.read_bytes() for path in old.glob(f"Logging{kind}RuntimeAudit*.class")}
        assert original_classes
        for name in builds:
            folder = ROOT / f"artifacts/{name}-build/classes/java/dialogue/net/yuuniverse/interactions"
            assert {path.name: path.read_bytes() for path in folder.glob(f"Logging{kind}RuntimeAudit*.class")} == original_classes
            regression_binaries.extend(evidence(folder / path) for path in original_classes)
    assert not git("diff", "0dce90e", "--", "neoforge/src/main", "neoforge/src/dialogue", "neoforge/src/test", "neoforge/build.gradle", "tools/audit-tests", "tools/audit-tests.gradle")
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
    sources = ["tools/record-logging-mysql-validation.py", "tools/capture-logging-mysql-fixture.py", "tools/logging-runtime-audit.gradle",
               "tools/record-logging-full-validation.py", "tools/record-logging-shutdown-validation.py", "tools/record-logging-index-validation.py",
               "tools/grieflogger-compat/run-storage-tests.ps1", "tools/prepare-logging-audit.ps1", "tools/prepare-logging-index-audit.ps1"]
    sources += [path.relative_to(ROOT).as_posix() for folder in ["tools/grieflogger-compat/storage-tests", "tools/logging-runtime-audit"] for path in (ROOT / folder).rglob("*.java")]
    for path in sources:
        assert (ROOT / path).read_bytes() == (ROOT / "artifacts/logging-mysql-references" / Path(path).relative_to("tools")).read_bytes()
    result = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", baseRevision="0dce90e", provider=evidence(PROVIDER),
                  providerCommit=git("rev-parse", "HEAD", cwd=source), patch=evidence(patch), unchangedDriverFiles=163, installedProviders=installed,
                  providerBuild=evidence("artifacts/grieflogger-mysql-command-provider-build.log"),
                  providerSources=[evidence(source / path) for path in git("diff", "--name-only", "c843228", "HEAD", cwd=source).splitlines()],
                  standalone=standalone, standaloneDatabases=[evidence(path) for path in databases],
                  standaloneClasses=[evidence(path) for path in sorted((ROOT / "artifacts/grieflogger-mysql-command-storage/classes").rglob("*.class"))],
                  mysqlStorage=dict(checks=38, log=evidence("artifacts/grieflogger-mysql-command-storage/mysql-tests.log")),
                  mysqlCommands=mysql, mysqlNormalLogs=normal_logs, sqliteRegressions=sqlite, mysqlNativeChecks=121, nativeChecks=308,
                  mysqlRestart=restart, snapshots=[evidence(f"artifacts/logging-mysql-{phase}/{database}/{file}") for phase in ["before-restart", "after-restart"] for database in restart["databases"] for file in ["snapshot.json", "database.sql"]],
                  inputSnapshots=[evidence(f"artifacts/logging-mysql-input-{case}/{file}") for case in ["before", "longtext", "text", "rejected"] for file in ["snapshot.json", "database.sql"]],
                  environment=[evidence("artifacts/logging-mysql84-environment/" + file) for file in ["image.json", "image-pull.log", "docker-version.json", "published-ports.json", "container-id.txt", "server-version.txt", "server-lifecycle.log", "container-lifecycle-events.jsonl", "commands-before.sql", "prepare-command-targets.sql", "prepare-extra-targets.sql", "prepare-longtext.sql", "prepare-rejected.sql"]],
                  runners=[evidence("artifacts/" + file) for file in ["start-logging-mysql84.ps1", "run-logging-mysql-audit.ps1", "run-logging-mysql-matrix.ps1", "run-logging-mysql-remaining.ps1", "restart-logging-mysql-fixture.ps1", "complete-logging-mysql-restart-record.ps1", "check-logging-mysql-guards.ps1", "confirm-logging-mysql-stopped.ps1"]],
                  retainedFailures=[evidence("artifacts/" + file) for file in ["logging-mysql-before-native.log", "logging-mysql-commands-before-mysql-commands.log", "grieflogger-mysql-command-unmodified-build.log"]],
                  guards=guards, stopped=stopped, sources=[evidence(path) for path in sources], auditBinaries=binaries, unchangedRegressionBinaries=regression_binaries, packages=previous["packages"],
                  reusedOrdinaryPerTarget=828, reusedInteractions=100, unchangedPinnedClient=previous["unchangedPinnedClient"],
                  limits=["Real MySQL 8.4.13, Connector/J 8.4.0, bounded UTF8 command, lock/constraint/read and graceful restart cases",
                          "Lost reply injected after actual COMMIT; no real network loss or MySQL crash claimed",
                          "Already truncated historical commands remain exactly truncated; no invented recovery",
                          "MySQL DDL implicitly commits; schema failure disables logging but cannot roll back successful preceding DDL",
                          "Custom narrow column semantics require review; supported widening preserves charset/collation",
                          "Sustained load/retention, all MySQL gameplay combinations and external-writer isolation remain unaccepted",
                          "No production activation, original migration completion, Paradigm inbound or deferred ordinary/VR client acceptance"])
    (ROOT / "docs/logging-mysql-validation-2026-10-23.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("PASS 156 SQLite + 38 MySQL storage checks; 308 fresh native/restart checks; exact MySQL persistence")


if __name__ == "__main__":
    validate()
