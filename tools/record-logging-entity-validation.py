"""Independently verify discriminated block/entity references and retained native history."""
from collections import Counter
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


base = module("entity_base", "tools/record-logging-full-validation.py")
shutdown = module("entity_shutdown", "tools/record-logging-shutdown-validation.py")
indexes = module("entity_indexes", "tools/record-logging-index-validation.py")
read, raw, evidence, git = base.read, base.raw, base.evidence, base.git
PROVIDER = "artifacts/grieflogger-entity-schema-source/neoforge/build/libs/grieflogger-1.2.10-1.21.1-yuuniverse.10-neoforge.jar"


def same_rows(left, right):
    encode = lambda rows: Counter(json.dumps(row, sort_keys=True, ensure_ascii=False) for row in rows)
    assert encode(left) == encode(right)


def log(name, phase, marker):
    path = f"artifacts/{name}-{phase}.log"
    text = raw(path)
    assert marker in text and "BUILD SUCCESSFUL" in text and "[LOGGINGAUDIT] FAILED" not in text, path
    assert "GriefLogger 1.2.10-1.21.1-yuuniverse.10" in text and "NeoForge 21.1.248" in text
    assert "logging admission disabled" not in text and "Failed to drain database writes after server stop" not in text
    assert "not committed" not in text
    return evidence(path)


def snapshot(database, phase="stopped"):
    return read(f"artifacts/logging-entity-mysql-{phase}/{database}/snapshot.json")


def generated(rows):
    for row in rows:
        assert len(row) == 10
        assert row[8:] == ([None, row[6]] if row[7] == 3 else [row[6], None])


def sqlite_registry(path):
    path = (ROOT / path).resolve()
    with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
        assert db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
        info = db.execute("PRAGMA table_xinfo(blocks)").fetchall()
        assert [row[1] for row in info] == ["time", "user", "level", "x", "y", "z", "type", "action", "material_type", "entity_type"]
        assert [row[6] for row in info[-2:]] == [2, 2]
        references = {row[3]: [row[2], row[4]] for row in db.execute("PRAGMA foreign_key_list(blocks)")}
        assert references == dict(user=["users", "id"], level=["levels", "id"], material_type=["materials", "id"], entity_type=["entities", "id"])
        assert db.execute("PRAGMA foreign_key_check(blocks)").fetchall() == []
        generated([list(row) for row in db.execute("SELECT * FROM blocks")])
        return dict(database=evidence(path), references=references, virtualColumns=[row[1] for row in info[-2:]], rows=db.execute("SELECT COUNT(*) FROM blocks").fetchone()[0])


def mysql_registry(saved):
    columns = saved["tables"]["blocks"]["columns"]
    assert [column["name"] for column in columns] == ["time", "user", "level", "x", "y", "z", "type", "action", "material_type", "entity_type"]
    expressions = ["casewhenaction=3thennullelsetypeend", "casewhenaction=3thentypeelsenullend"]
    for column, expression in zip(columns[-2:], expressions, strict=True):
        assert column["extra"] == "STORED GENERATED" and re.sub(r"[\s`()]+", "", column["generation"].lower()) == expression
    references = {row["column"]: [row["targetTable"], row["targetColumn"]] for row in saved["references"] if row["table"] == "blocks"}
    assert references == dict(user=["users", "id"], level=["levels", "id"], material_type=["materials", "id"], entity_type=["entities", "id"])
    assert all(row["targetSchema"] == saved["database"] for row in saved["references"])
    generated(saved["tables"]["blocks"]["rows"])
    return dict(database=saved["database"], references=references, storedColumns=[column["name"] for column in columns[-2:]], rows=len(saved["tables"]["blocks"]["rows"]))


def entity_acceptance():
    result = []
    for backend in ["mysql", "sqlite"]:
        name = "logging-entity-" + backend + "-fresh-final"
        fixture = Path("artifacts") / (name + "-server")
        state = read(fixture / "logging-entity-state.json")
        recovered = read(fixture / "logging-entity-recovery.json")
        assert state["checks"] == 20 and recovered["checks"] == 9 and state["pending"] == 0 and state["flushed"]
        assert state["original"] == dict(materials=[], entities=[], blocks=[])
        assert [kill["type"] for kill in state["kills"]] == ["pig", "cow", "sheep", "pig", "chicken"]
        assert len({kill["entity"] for kill in state["kills"]}) == 5
        tables = state["tables"]
        assert len(tables["materials"]) == 1 and tables["materials"][0][1] == "stone"
        assert len(tables["entities"]) == 4 and len(tables["blocks"]) == 6
        generated(tables["blocks"])
        entities, materials = dict(tables["entities"]), dict(tables["materials"])
        assert Counter(entities[row[6]] for row in tables["blocks"] if row[7] == 3) == Counter(pig=2, cow=1, sheep=1, chicken=1)
        assert Counter(tuple(row[3:6]) for row in tables["blocks"] if row[7] == 3) == Counter((kill["x"], kill["y"], kill["z"]) for kill in state["kills"])
        assert [materials[row[6]] for row in tables["blocks"] if row[7] == 1] == ["stone"]
        if backend == "mysql":
            saved = snapshot("loggingaudit_entity_fresh_final")
            registry = mysql_registry(saved)
            for table in ["materials", "entities", "blocks"]:
                same_rows(saved["tables"][table]["rows"], tables[table])
            assert saved["tables"]["users"]["rows"] == [[1, "EntityActor", state["actor"]]]
        else:
            registry = sqlite_registry(fixture / "database.db")
            with sqlite3.connect((ROOT / fixture / "database.db").as_uri() + "?mode=ro&immutable=1", uri=True) as db:
                for table in ["materials", "entities", "blocks"]:
                    same_rows([list(row) for row in db.execute("SELECT * FROM " + table)], tables[table])
                assert db.execute("SELECT name,uuid FROM users").fetchall() == [("EntityActor", state["actor"])]
        logs = [log(name, phase, f"[LOGGINGENTITY] COMPLETE {count} checks phase={phase}") for phase, count in [("entities", 20), ("entities-recover", 9)]]
        result.append(dict(backend=backend, state=evidence(fixture / "logging-entity-state.json"), recovery=evidence(fixture / "logging-entity-recovery.json"), registry=registry, logs=logs))
    before = Path("artifacts/logging-entity-mysql-before-final-server")
    observed = read(before / "logging-entity-observed.json")
    assert observed["flushed"] and observed["pending"] == 0
    assert observed["afterFirstKills"]["materials"] == [] and len(observed["afterFirstKills"]["entities"]) == 3 and observed["afterFirstKills"]["blocks"] == []
    before_sql = snapshot("loggingaudit")
    for table, rows in observed["afterFirstKills"].items():
        same_rows(before_sql["tables"][table]["rows"], rows)
    failed = raw("artifacts/logging-entity-mysql-before-final-entities.log")
    assert "AssertionError: all_native_kills_persist_without_material_dictionary_rows" in failed and "BUILD FAILED" in failed
    return dict(accepted=result, reproduced=dict(observed=evidence(before / "logging-entity-observed.json"), log=evidence("artifacts/logging-entity-mysql-before-final-entities.log")))


def sqlite_regressions():
    normal_name = "logging-entity-sqlite-normal"
    fixture = Path("artifacts") / (normal_name + "-server")
    normal_state = read(fixture / "logging-restart-state.json")
    normal = dict(databases=base.database_evidence(fixture, normal_state, True),
                  logs=[log(normal_name, phase, "[LOGGINGAUDIT] COMPLETE " + marker) for phase, marker in [("native", "37 checks"), ("recover", "8 restart checks")]])
    stop_name = "logging-entity-sqlite-shutdown"
    stop_fixture = Path("artifacts") / (stop_name + "-server")
    state = read(stop_fixture / "logging-shutdown-result.json")
    recovery = read(stop_fixture / "logging-shutdown-recovery.json")
    assert state["checks"] == 11 and recovery["recoveryChecks"] == 8 and all(recovery[key] == state[key] for key in state)
    stop = []
    for phase, count, sessions in [("shutdown", 11, {0: 1, 1: 1}), ("shutdown-recover", 8, {0: 2, 1: 2})]:
        prefix = f"artifacts/{stop_name}-stopped-{phase}"
        stop.append(dict(log=log(stop_name, phase, f"[LOGGINGSHUTDOWN] COMPLETE {count} checks phase={phase}"),
                         database=shutdown.database(prefix + ".db", state, sessions), nativeStorage=shutdown.native_storage(Path(prefix + "-world"), state)))
    index_name = "logging-entity-sqlite-index"
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
    read_name = "logging-entity-sqlite-read"
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


def mysql_normal():
    normal_logs = [log("logging-entity-mysql-normal-final", phase, "[LOGGINGAUDIT] COMPLETE " + marker) for phase, marker in [("native", "37 checks"), ("recover", "8 restart checks")]]
    normal_fixture = Path("artifacts/logging-entity-mysql-normal-final-server")
    # The logging database is MySQL; validate the independent Economy ledger directly.
    state = read(normal_fixture / "logging-restart-state.json")
    native_tables = snapshot("loggingaudit_entity_normal_final")["tables"]
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
    registry = mysql_registry(snapshot("loggingaudit_entity_normal_final"))
    return dict(logs=normal_logs, state=evidence(normal_fixture / "logging-restart-state.json"), registry=registry, checks=45)


def migrations():
    accepted = []
    for backend in ["mysql", "sqlite"]:
        name = f"logging-entity-{backend}-migration-final"
        fixture = Path("artifacts") / (name + "-server")
        original = read(fixture / "logging-entity-migration-original.json")
        state = read(fixture / "logging-entity-migration-state.json")
        recovered = read(fixture / "logging-entity-migration-recovery.json")
        assert state == recovered and state["checks"] == 5 and state["tables"] == original
        assert len(original["blocks"]) == 199
        if backend == "sqlite":
            source = Path("artifacts/logging-shutdown-normal-regression-server/database.db")
            assert indexes.snapshot(source) == indexes.snapshot(fixture / "database.db")
            registry = sqlite_registry(fixture / "database.db")
            with sqlite3.connect((ROOT / source).as_uri() + "?mode=ro&immutable=1", uri=True) as before, sqlite3.connect((ROOT / fixture / "database.db").as_uri() + "?mode=ro&immutable=1", uri=True) as after:
                old = before.execute("SELECT type,name,sql FROM sqlite_master WHERE tbl_name='blocks' AND type<>'table' ORDER BY type,name").fetchall()
                new = after.execute("SELECT type,name,sql FROM sqlite_master WHERE tbl_name='blocks' AND type<>'table' ORDER BY type,name").fetchall()
                assert old == new
        else:
            before = read("artifacts/logging-entity-mysql-migration-input/snapshot.json")
            after = snapshot("loggingaudit_entity_migration_final")
            registry = mysql_registry(after)
            for table, prior in before["tables"].items():
                current = after["tables"][table]
                same_rows(prior["rows"], [row[:8] for row in current["rows"]] if table == "blocks" else current["rows"])
                assert prior["columns"] == current["columns"][:8] if table == "blocks" else prior["columns"] == current["columns"]
            assert all(index in after["indices"] for index in before["indices"])
            assert before["triggers"] == after["triggers"] and before["attributes"] == after["attributes"]
        logs = [log(name, phase, f"[LOGGINGENTITYMIGRATION] COMPLETE 5 checks phase={phase}") for phase in ["entities-migrate", "entities-migrate-recover"]]
        accepted.append(dict(backend=backend, original=evidence(fixture / "logging-entity-migration-original.json"), state=evidence(fixture / "logging-entity-migration-state.json"), recovery=evidence(fixture / "logging-entity-migration-recovery.json"), registry=registry, logs=logs))
    return accepted


def rejected():
    result = []
    for backend in ["mysql", "sqlite"]:
        name = f"logging-entity-{backend}-reject-final"
        fixture = Path("artifacts") / (name + "-server")
        original = read(fixture / "logging-entity-rejection-original.json")
        state = read(fixture / "logging-entity-rejection.json")
        assert state == dict(**original, checks=4)
        assert len(state["tables"]["blocks"]) == 199 and "audit_note" in str(state["schema"])
        text = raw(f"artifacts/{name}-entities-reject.log")
        assert "[LOGGINGENTITYREJECTION] COMPLETE 4 checks phase=entities-reject" in text
        assert "Unsupported blocks" in text and "logging admission disabled" in text and "BUILD SUCCESSFUL" in text
        assert "[LOGGINGAUDIT] FAILED" not in text
        if backend == "mysql":
            saved = snapshot("loggingaudit_entity_reject_final")
            for table, rows in state["tables"].items():
                same_rows(rows, saved["tables"][table]["rows"])
            assert not any(index["table"] == "blocks" and index["name"] == "coordinates" for index in saved["indices"])
        else:
            with sqlite3.connect((ROOT / fixture / "database.db").as_uri() + "?mode=ro&immutable=1", uri=True) as db:
                assert [list(row) for row in db.execute("SELECT type,name,sql FROM sqlite_master WHERE tbl_name='blocks' ORDER BY type,name")] == original["schema"]
        result.append(dict(backend=backend, original=evidence(fixture / "logging-entity-rejection-original.json"), result=evidence(fixture / "logging-entity-rejection.json"), log=evidence(f"artifacts/{name}-entities-reject.log")))
    return result


def commands():
    name = "logging-entity-mysql-commands-final"
    fixture = Path("artifacts") / (name + "-server")
    state = read(fixture / "logging-mysql-command-state.json")
    recovered = read(fixture / "logging-mysql-command-recovery.json")
    assert state["checks"] == 13 and recovered["checks"] == 5 and state["pending"] == 0
    assert state["column"] == recovered["column"] and state["column"]["DATA_TYPE"] == "mediumtext"
    assert [len(value.encode("utf-8")) for value in state["inputs"]] == [23, 1032, 69025]
    assert [row["command"] for row in state["commands"]] == state["inputs"]
    previous = module("entity_mysql", "tools/record-logging-mysql-validation.py")
    saved = snapshot("loggingaudit_entity_commands_final")
    same_rows(previous.command_rows(saved), state["allCommands"])
    logs = [log(name, phase, f"[MYSQLCOMMANDAUDIT] COMPLETE {count} checks phase={phase}") for phase, count in [("mysql-commands", 13), ("mysql-commands-recover", 5)]]
    return dict(state=evidence(fixture / "logging-mysql-command-state.json"), recovery=evidence(fixture / "logging-mysql-command-recovery.json"), logs=logs, registry=mysql_registry(saved), checks=18)


def validate():
    source = ROOT / "artifacts/grieflogger-entity-schema-source"
    patch = "tools/grieflogger-compat/entity-registry-references.patch"
    assert not git("status", "--porcelain", cwd=source)
    assert git("log", "-1", "--format=%an <%ae>|%cn <%ce>", cwd=source) == "A-BenLi06 <ben_li06@outlook.com>|A-BenLi06 <ben_li06@outlook.com>"
    for checkout, reverse in [(source, ["--reverse"]), (ROOT / "artifacts/grieflogger-mysql-command-source", [])]:
        subprocess.run(["git", "apply", *reverse, "--check", str(ROOT / patch)], cwd=checkout, check=True)
    provider = evidence(PROVIDER)
    assert evidence("artifacts/grieflogger-entity-schema-release/provider.jar")["sha256"] == provider["sha256"]
    previous = read("docs/logging-mysql-validation-2026-10-23.json")
    assert evidence(previous["provider"]["path"]) == previous["provider"]
    drivers = []
    for path in [PROVIDER, previous["provider"]["path"]]:
        with zipfile.ZipFile(ROOT / path) as archive:
            assert archive.testzip() is None and not any(name.startswith("org/sqlite/") for name in archive.namelist())
            driver = next(item for item in json.loads(archive.read("META-INF/jarjar/metadata.json"))["jars"] if item["identifier"] == dict(group="org.xerial", artifact="sqlite-jdbc"))
            with zipfile.ZipFile(io.BytesIO(archive.read(driver["path"]))) as nested:
                drivers.append({name: hashlib.sha256(nested.read(name)).hexdigest() for name in nested.namelist() if not name.endswith("/")})
    assert drivers[0] == drivers[1] and len(drivers[0]) == 163
    assert "BUILD SUCCESSFUL" in raw("artifacts/grieflogger-entity-schema-fenced-build.log")
    standalone = read("artifacts/grieflogger-entity-schema-fenced/validation.json")
    assert standalone["checks"] == 156 and standalone["actualProviderBytecode"]
    for item in standalone["files"]:
        path = Path(item["path"])
        assert len(path.read_bytes()) == item["bytes"] and hashlib.sha256(path.read_bytes()).hexdigest() == item["sha256"]
    storage = Path("artifacts/grieflogger-entity-schema-fenced")
    for filename, marker in [("schema-sqlite.log", "[BLOCKSCHEMA] COMPLETE 28 checks backend=sqlite"), ("schema-mysql.log", "[BLOCKSCHEMA] COMPLETE 26 checks backend=mysql"), ("mysql-tests.log", "[MYSQLSTORAGE] COMPLETE 38 checks")]:
        assert marker in raw(storage / filename)
    assert "SERVER 8.4.13 DRIVER mysql-connector-j-8.4.0" in raw(storage / "mysql-tests.log")
    sqlite_databases = sorted((ROOT / storage / "databases").glob("*.db"))
    assert len(sqlite_databases) == 32
    for path in sqlite_databases:
        with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
            assert db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
    stored = snapshot("loggingaudit_storage_fenced")["tables"]
    for table in ["observations_ordinary_lock", "observations_batch_lock"]:
        same_rows(stored[table]["rows"], [[1, "updated", None], [2, "retained 汉🙂", "010203"]])
    same_rows(stored["observations_constraint"]["rows"], [[1, None, "04"], [2, "second", "05"]])
    same_rows(stored["observations_uncertain"]["rows"], [[1, "actually committed", None]])
    same_rows(stored["users"]["rows"], [[1, "committed 汉", None]])
    native = dict(entities=entity_acceptance(), migrations=migrations(), rejected=rejected(), commands=commands(), mysqlNormal=mysql_normal(), sqliteRegressions=sqlite_regressions())
    builds = [f"logging-entity-{backend}-{kind}-final" for backend in ["mysql", "sqlite"] for kind in ["fresh", "migration", "reject"]]
    builds += ["logging-entity-mysql-normal-final", "logging-entity-mysql-commands-final"]
    builds += ["logging-entity-sqlite-" + kind for kind in ["normal", "shutdown", "index", "read"]]
    installed = []
    for name in builds:
        jars = list((ROOT / f"artifacts/{name}-server/mods").glob("grieflogger-*.jar"))
        assert len(jars) == 1 and evidence(jars[0])["sha256"] == provider["sha256"], name
        installed.append(evidence(jars[0]))
        if "sqlite" in name and "reject" not in name:
            sqlite_registry(Path("artifacts") / (name + "-server/database.db"))
    entity_classes = [evidence(f"artifacts/{name}-build/classes/java/dialogue/net/yuuniverse/interactions/LoggingEntityRuntimeAudit.class") for name in ["logging-entity-mysql-before-final", "logging-entity-mysql-fresh-final", "logging-entity-sqlite-fresh-final"]]
    assert len({item["sha256"] for item in entity_classes}) == 1
    assert not git("diff", "29b63eb", "--", "neoforge/src/main", "neoforge/src/dialogue", "neoforge/src/test", "neoforge/build.gradle", "tools/audit-tests", "tools/audit-tests.gradle")
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
    old_raw = read("artifacts/logging-mysql-container-restart.json")
    correction = read("artifacts/logging-entity-prior-mysql-brief-restart.json")
    assert correction["allOriginalSqlRowsUnchanged"] and correction["tables"] == 106 and len(correction["databases"]) == 10 and correction["exitCode"] == 0
    changed = {item["path"]: item for item in correction["internalFileChanges"]}
    assert len(changed) == 8
    for item in old_raw["retainedData"]:
        if item["path"] in changed:
            assert evidence(item["path"])["sha256"] == changed[item["path"]]["currentSha256"]
        else:
            assert evidence(item["path"]) == item
    prior_current = read("artifacts/logging-entity-prior-mysql-current-files.json")
    assert prior_current["stopped"] and prior_current["containerId"] == correction["containerId"]
    assert len(prior_current["files"]) == 280 and len(prior_current["additionalFilesSinceEarlierStop"]) == 1
    for item in prior_current["files"]:
        assert evidence(item["path"]) == item
    for database in old_raw["databases"]:
        for phase in ["before-restart", "after-restart"]:
            for file in ["snapshot.json", "database.sql"]:
                path = f"artifacts/logging-mysql-{phase}/{database}/{file}"
                pinned = next(item for item in previous["snapshots"] if item["path"] == path)
                assert evidence(path) == pinned
    stopped = read("artifacts/logging-entity-stopped.json")
    assert set(name + "-server" for name in builds).issubset({record["fixture"] for record in stopped["fixtures"]})
    for record in stopped["fixtures"] + stopped["snapshots"]:
        assert record["exclusive"]
        for item in record["files"]:
            assert evidence(item["path"]) == item
    guards = read("artifacts/logging-entity-launch-rejections.json")
    assert len(guards["checks"]) == 6 and guards["before"] == guards["after"]
    for check in guards["checks"]:
        assert check["exitCode"] != 0 and check["expected"] in raw(check["log"]) and "Starting minecraft server" not in raw(check["log"])
    for path, digest in guards["before"].items():
        assert evidence(path)["sha256"].upper() == digest
    lifecycle = read("artifacts/logging-entity-mysql-stopped.json")
    assert lifecycle["finalStopped"] and lifecycle["finalPortReleased"] and lifecycle["exitCode"] == 0
    assert "Shutdown complete" in raw("artifacts/logging-entity-mysql84-environment/server-lifecycle.log")
    for item in lifecycle["retainedData"]:
        assert evidence(item["path"]) == item
    captures = []
    for folder in sorted((ROOT / "artifacts/logging-entity-mysql-stopped").iterdir()):
        saved = read(folder / "snapshot.json")
        assert saved["containerId"] == lifecycle["containerId"] and saved["server"]["version"] == "8.4.13"
        captures.extend(evidence(folder / file) for file in ["snapshot.json", "database.sql"])
    sources = ["tools/record-logging-entity-validation.py", "tools/record-logging-mysql-validation.py", "tools/capture-logging-mysql-fixture.py", "tools/logging-runtime-audit.gradle", "tools/record-logging-full-validation.py", "tools/record-logging-shutdown-validation.py", "tools/record-logging-index-validation.py", "tools/grieflogger-compat/run-storage-tests.ps1", "tools/prepare-logging-audit.ps1", "tools/prepare-logging-index-audit.ps1"]
    sources += [path.relative_to(ROOT).as_posix() for folder in ["tools/grieflogger-compat/storage-tests", "tools/logging-runtime-audit"] for path in (ROOT / folder).rglob("*.java")]
    for path in sources:
        assert (ROOT / path).read_bytes() == (ROOT / "artifacts/logging-entity-references" / Path(path).relative_to("tools")).read_bytes()
    schema_databases = sorted((ROOT / storage / "schema-databases").glob("*.db"))
    assert len(schema_databases) == 4
    for path in schema_databases:
        with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
            assert db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
    excluded = [evidence(path) for path in sorted((ROOT / "artifacts/grieflogger-entity-schema-prototype").iterdir()) if path.is_file()]
    excluded += [evidence(next((ROOT / "artifacts/logging-entity-mysql-commands-server/mods").glob("grieflogger-*.jar")))]
    fixture_scripts = ["run-logging-entity-mysql-audit.ps1", "run-logging-entity-regressions.ps1", "run-logging-entity-final.ps1", "prepare-logging-entity-final.ps1", "prepare-logging-entity-rejection.py", "stop-logging-entity-mysql.ps1", "confirm-logging-entity-stopped.ps1", "check-logging-entity-guards.ps1", "verify-prior-mysql-restart.py", "run-logging-entity-fenced-storage.ps1", "complete-logging-entity-fenced-storage.ps1"]
    environment = ["identity.json", "image.json", "published-ports.json", "server-lifecycle.log", "prepare-schema-fenced.sql", "prepare-native-final.sql"]
    result = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", baseRevision="29b63eb", provider=provider, providerCommit=git("rev-parse", "HEAD", cwd=source), patch=evidence(patch), unchangedDriverFiles=163,
                  installedProviders=installed, providerBuild=evidence("artifacts/grieflogger-entity-schema-fenced-build.log"),
                  providerSources=[evidence(source / path) for path in git("diff", "--name-only", "16f0788", "HEAD", cwd=source).splitlines()],
                  standalone=standalone, standaloneDatabases=[evidence(path) for path in sqlite_databases], standaloneChecks=248,
                  schemaAndStorageLogs=[evidence(storage / file) for file in ["schema-sqlite.log", "schema-mysql.log", "mysql-tests.log"]],
                  standaloneClasses=[evidence(path) for path in sorted((ROOT / storage / "classes").rglob("*.class"))],
                  standaloneSchemaDatabases=[evidence(path) for path in schema_databases], excludedPrototypeAndIntermediate=excluded,
                  retainedRunnerFailures=[evidence(path) for path in ["artifacts/grieflogger-entity-fenced-storage-run.log", "artifacts/logging-entity-sqlite-reject-final-compile.log"]],
                  fixtureScripts=[evidence("artifacts/" + path) for path in fixture_scripts], environment=[evidence("artifacts/logging-entity-mysql84-environment/" + path) for path in environment],
                  native=native, nativeChecks=336, mysqlNativeChecks=106, sqliteNativeChecks=230, entityAuditBytecode=entity_classes,
                  mysqlStopped=lifecycle, snapshots=captures, stopped=stopped, guards=guards,
                  inputSnapshots=[evidence("artifacts/logging-entity-mysql-migration-input/" + file) for file in ["snapshot.json", "database.sql"]],
                  previousValidation=evidence("docs/logging-mysql-validation-2026-10-23.json"),
                  priorFixtureCorrection=evidence("artifacts/logging-entity-prior-mysql-brief-restart.json"),
                  priorFixtureCurrentFiles=prior_current,
                  sources=[evidence(path) for path in sources], references=[evidence(ROOT / "artifacts/logging-entity-references" / Path(path).relative_to("tools")) for path in sources],
                  packages=previous["packages"], reusedOrdinaryPerTarget=828, reusedInteractions=100, unchangedPinnedClient=previous["unchangedPinnedClient"],
                  limits=["Bounded MySQL 8.4.13 and embedded SQLite acceptance; prototype and intermediate artifacts are retained but excluded from final check totals", "No lost historical deaths are invented or recovered", "SQLite native foreign-key configuration is unchanged; retained references are checked explicitly at startup", "MySQL DDL implicitly commits; unsupported startup is fenced, not a database-wide atomic migration", "Standalone Minecraft bootstrap/logger owner substituted; actual provider SQL/queue/schema bytecode and drivers used", "Prior MySQL fixture was briefly restarted before isolation; exact 106-table SQL preservation and eight changed internal-file hashes are recorded separately", "No production activation, sustained load/retention, physical/network crash recovery, original quest/item migration completion, Paradigm inbound or deferred ordinary/VR client acceptance"])
    destination = ROOT / "docs/logging-entity-validation-2026-10-23.json"
    destination.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("PASS entity registry references: 248 standalone and 336 native/restart checks; exact histories and stopped ownership verified")


if __name__ == "__main__":
    validate()
