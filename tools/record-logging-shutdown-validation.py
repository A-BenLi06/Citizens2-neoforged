"""Validate native normal-stop logging, retained SQLite rows and stopped Minecraft NBT."""
from collections import Counter
from datetime import datetime
from pathlib import Path
import gzip
import hashlib
import importlib.util
import io
import json
import sqlite3
import subprocess
import sys
import zipfile
import zlib

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


base = module("logging_full_evidence", "tools/record-logging-full-validation.py")
native = module("native_nbt_reader", "tools/record-guard-restart-validation.py")
read, raw, evidence, files, git = base.read, base.raw, base.evidence, base.files, base.git


def sql_rows(db, owner):
    result = []
    for table in ["blocks", "containers", "items"]:
        columns = "m.name,r.action,0,NULL" if table == "blocks" else "m.name,r.action,r.amount,hex(r.data)"
        query = f"SELECT r.time,r.x,r.y,r.z,{columns} FROM {table} r JOIN users u ON r.user=u.id JOIN materials m ON r.type=m.id WHERE u.uuid=? ORDER BY r.time,r.x,r.y,r.z,m.name,r.action"
        result.extend([[table, *row] for row in db.execute(query, (owner,))])
    return result


def database(path, state=None, expected_sessions=None):
    path = ROOT / path
    assert not any(Path(str(path) + suffix).exists() for suffix in ["-wal", "-shm", "-journal"])
    with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
        assert db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
        result = dict(**evidence(path), quickCheck="ok")
        if state:
            rows = sql_rows(db, state["actor"])
            result["gameplayRows"] = rows
            if "committedRows" in state:
                assert rows == state["committedRows"]
            sessions = dict(db.execute("SELECT s.action,COUNT(*) FROM sessions s JOIN users u ON s.user=u.id WHERE u.uuid=? GROUP BY s.action", (state["actor"],)))
            if expected_sessions is not None:
                assert sessions == expected_sessions
            result["sessions"] = sessions
    return result


def chunk_images(world):
    result = []
    for region in sorted((ROOT / world / "region").glob("*.mca")):
        data = region.read_bytes()
        if not data:
            continue
        assert len(data) >= 8192
        for index in range(1024):
            location = int.from_bytes(data[index*4:index*4+4], "big")
            if not location:
                continue
            offset, sectors = location >> 8, location & 255
            start = offset * 4096
            size = int.from_bytes(data[start:start+4], "big")
            assert offset >= 2 and sectors > 0 and 1 < size <= sectors*4096-4 and start+size+4 <= len(data)
            payload, compression = data[start+5:start+4+size], data[start+4]
            if compression == 1:
                payload = gzip.decompress(payload)
            elif compression == 2:
                payload = zlib.decompress(payload)
            else:
                assert compression == 3
            result.append(native.nbt(payload))
    return result


def native_storage(world, state):
    expected = state["storage"]
    player_file = ROOT / world / "playerdata" / (state["actor"] + ".dat")
    player = native.nbt(gzip.decompress(player_file.read_bytes()))
    assert native.native_uuid(player["UUID"]) == state["actor"]
    inventory = [{} for _ in expected["inventory"]]
    for saved in player["Inventory"]:
        saved = dict(saved)
        slot = saved.pop("Slot")
        slot = slot if 0 <= slot < 36 else slot - 100 + 36 if 100 <= slot <= 103 else 40 if slot == -106 else -1
        assert 0 <= slot < len(inventory) and not inventory[slot]
        inventory[slot] = saved
    assert inventory == expected["inventory"]
    drops = [e for e in native.entity_regions(world) if native.native_uuid(e["UUID"]) == state["dropEntity"]]
    assert len(drops) == 1 and drops[0]["id"] == "minecraft:item" and drops[0]["Item"] == expected["dropStack"]
    assert bool(drops[0]["NoGravity"]) == expected["dropNoGravity"]
    chunks = [chunk for chunk in chunk_images(world) if chunk["xPos"] == 0 and chunk["zPos"] == 0]
    assert len(chunks) == 1
    section = next(section for section in chunks[0]["sections"] if section["Y"] == -4)
    palette = section["block_states"]["palette"]
    bits = max(4, (len(palette)-1).bit_length())
    actual_blocks = []
    for x, y, z in [(3,-60,3), (5,-60,3), (7,-60,3), (11,-60,3)]:
        index = (y & 15)*256 + z*16 + x
        if len(palette) == 1:
            actual = palette[0]
        else:
            per_long = 64 // bits
            packed = section["block_states"]["data"][index // per_long] & ((1 << 64)-1)
            actual = palette[(packed >> ((index % per_long)*bits)) & ((1 << bits)-1)]
        actual_blocks.append(actual)
    assert actual_blocks == expected["blocks"]
    chest = [value for value in chunks[0]["block_entities"] if (value["x"],value["y"],value["z"]) == (7,-60,3)]
    assert len(chest) == 1 and chest[0]["id"] == "minecraft:chest" and not chest[0]["Items"]
    assert all(not value for value in expected["chest"])
    return dict(player=evidence(player_file), inventory=inventory, originalDrop=drops[0]["Item"],
                dropEntity=state["dropEntity"], blocks=actual_blocks, chestEmpty=True,
                entityFiles=files(Path(world) / "entities"), regionFiles=files(Path(world) / "region"))


def validate():
    source5 = ROOT / "artifacts/grieflogger-shutdown-source"
    source6 = ROOT / "artifacts/grieflogger-shutdown-item-source"
    provider5 = "artifacts/grieflogger-shutdown-source/neoforge/build/libs/grieflogger-1.2.10-1.21.1-yuuniverse.5-neoforge.jar"
    provider6 = "artifacts/grieflogger-shutdown-item-source/neoforge/build/libs/grieflogger-1.2.10-1.21.1-yuuniverse.6-neoforge.jar"
    patches = ["tools/grieflogger-compat/server-stop-drain.patch", "tools/grieflogger-compat/player-quit-item-drain.patch"]
    for source, parent, patch in [(source5, ROOT / "artifacts/grieflogger-full-recovery-source", patches[0]), (source6, source5, patches[1])]:
        assert not git("status", "--porcelain", cwd=source)
        assert git("log", "-1", "--format=%an <%ae>|%cn <%ce>", cwd=source) == "A-BenLi06 <ben_li06@outlook.com>|A-BenLi06 <ben_li06@outlook.com>"
        subprocess.run(["git","apply","--reverse","--check",str(ROOT / patch)],cwd=source,check=True)
        subprocess.run(["git","apply","--check",str(ROOT / patch)],cwd=parent,check=True)
    build_logs = ["artifacts/grieflogger-shutdown"+suffix+"-provider-build.log" for suffix in ["","-item"]]
    for path in build_logs:
        assert "BUILD SUCCESSFUL" in raw(path)
    driver_files = []
    previous = read("docs/logging-full-recovery-validation-2026-10-23.json")
    assert evidence(previous["provider"]["path"]) == previous["provider"]
    for provider in [previous["provider"]["path"], provider5, provider6]:
        with zipfile.ZipFile(ROOT / provider) as archive:
            assert not any(name.startswith("org/sqlite/") for name in archive.namelist())
            drivers = [d for d in json.loads(archive.read("META-INF/jarjar/metadata.json"))["jars"] if d["identifier"] == dict(group="org.xerial",artifact="sqlite-jdbc")]
            assert len(drivers) == 1
            with zipfile.ZipFile(io.BytesIO(archive.read(drivers[0]["path"]))) as driver:
                driver_files.append({n:driver.read(n) for n in driver.namelist() if not n.endswith("/")})
    assert driver_files[0] == driver_files[1] == driver_files[2]
    standalone = read("artifacts/grieflogger-shutdown-item-final/validation.json")
    assert standalone["checks"] == 109 and standalone["actualProviderBytecode"]
    for item in standalone["files"]:
        assert hashlib.sha256(Path(item["path"]).read_bytes()).hexdigest() == item["sha256"]
    standalone_dbs = [database(path) for path in sorted((ROOT / "artifacts/grieflogger-shutdown-item-final/databases").glob("*.db"))]
    assert len(standalone_dbs) == 24
    failures = []
    for prefix, version, expected, counts, sessions in [
            ("logging-shutdown-before", ".4", "normal_server_stop_drains_both_original_queues", dict(blocks=1), {0:1}),
            ("logging-shutdown-final", ".5", "normal_stop_commits_all_seven_original_gameplay_records", dict(blocks=5,containers=1), {0:1,1:1})]:
        fixture = Path("artifacts") / (prefix + "-server")
        state = read(fixture / "logging-shutdown-state.json")
        text = raw(f"artifacts/{prefix}-shutdown.log")
        assert "GriefLogger 1.2.10-1.21.1-yuuniverse" + version in text
        assert "AssertionError: " + expected in text and "[LOGGINGSHUTDOWN] COMPLETE" not in text
        db = database(fixture / "database.db", state, sessions)
        assert Counter(row[0] for row in db["gameplayRows"]) == counts
        failures.append(dict(version=version,state=state,log=evidence(f"artifacts/{prefix}-shutdown.log"),database=db,
                             nativeStorage=native_storage(fixture / "logging-world",state)))
    fixture = Path("artifacts/logging-shutdown-item-final-server")
    seed = read(fixture / "logging-shutdown-result.json")
    recovered = read(fixture / "logging-shutdown-recovery.json")
    assert seed["checks"] == 11 and recovered["recoveryChecks"] == 8
    assert all(recovered[key] == seed[key] for key in seed)
    assert seed["ordinaryPendingAtStop"] > 0 and seed["batchPendingAtStop"] > 0
    assert Counter(row[0] for row in seed["committedRows"]) == dict(blocks=5,containers=1,items=1)
    for row in seed["committedRows"]:
        if row[0] in ["containers","items"]:
            expected_name = b"Shutdown paper" if row[0] == "containers" else b"Shutdown snow"
            assert row[7] == (7 if row[0] == "containers" else 3) and expected_name in bytes.fromhex(row[8])
    shutdown_logs = [f"artifacts/logging-shutdown-item-final-{phase}.log" for phase in ["shutdown","shutdown-recover"]]
    for path, phase, checks in zip(shutdown_logs,["shutdown","shutdown-recover"],[11,8]):
        text = raw(path)
        assert f"[LOGGINGSHUTDOWN] COMPLETE {checks} checks phase={phase}" in text and "BUILD SUCCESSFUL" in text
        assert "[LOGGINGAUDIT] FAILED" not in text and "not committed" not in text
        assert "Failed to drain database writes after server stop" not in text
        assert "GriefLogger 1.2.10-1.21.1-yuuniverse.6" in text
    shutdown = dict(state=seed,recovered=recovered,logs=[evidence(p) for p in shutdown_logs],
                    database=database(fixture / "database.db",seed,{0:2,1:2}),
                    nativeStorage=native_storage(fixture / "logging-world",seed),snapshots=[])
    for phase, sessions in [("shutdown",{0:1,1:1}),("shutdown-recover",{0:2,1:2})]:
        world = Path(f"artifacts/logging-shutdown-item-final-stopped-{phase}-world")
        shutdown["snapshots"].append(dict(phase=phase,nativeStorage=native_storage(world,seed),
            database=database(f"artifacts/logging-shutdown-item-final-stopped-{phase}.db",seed,sessions)))
    regressions = []
    for mode, checks in [("full",27),("reader",22),("writer",22)]:
        prefix = "logging-shutdown-" + mode + "-regression"
        directory = Path("artifacts") / (prefix + "-server")
        state, restored = read(directory / "logging-storage-state.json"), read(directory / "logging-storage-recovery.json")
        assert state["checks"] == checks and restored["recoveryChecks"] == 11 and state["lockMode"] == mode
        assert all(restored[key] == state[key] for key in state)
        assert restored["storage"] == state["storage"] and restored["recoveredStorage"] == state["storage"] and restored["recoveredRows"] == state["committedRows"]
        assert state["lockedOrdinaryPending"] > 0 and state["lockedBatchPending"] > 0
        assert Counter(row[0] for row in state["committedRows"]) == dict(blocks=5,containers=1,items=1)
        paths = [f"artifacts/{prefix}-{phase}.log" for phase in ["storage","storage-recover"]]
        for path, phase, count in zip(paths,["storage","storage-recover"],[checks,11]):
            assert f"[LOGGINGSTORAGE] COMPLETE {count} checks phase={phase}" in raw(path) and "BUILD SUCCESSFUL" in raw(path)
            assert "[LOGGINGAUDIT] FAILED" not in raw(path) and "write outcome requires review" not in raw(path)
            assert "not committed" not in raw(path) and "Failed to drain database writes after server stop" not in raw(path)
            assert "NeoForge 21.1.248" in raw(path) and "GriefLogger 1.2.10-1.21.1-yuuniverse.6" in raw(path)
            if phase == "storage":
                assert ("[SQLITE_FULL]" if mode == "full" else "[SQLITE_BUSY]") in raw(path)
        regressions.append(dict(mode=mode,state=state,recovered=restored,logs=[evidence(p) for p in paths],databases=base.database_evidence(directory,state)))
    directory = Path("artifacts/logging-shutdown-normal-regression-server")
    state = read(directory / "logging-restart-state.json")
    normal_logs = [f"artifacts/logging-shutdown-normal-regression-{phase}.log" for phase in ["native","recover"]]
    for path, marker in zip(normal_logs,["37 checks","8 restart checks"]):
        assert "[LOGGINGAUDIT] COMPLETE " + marker in raw(path) and "BUILD SUCCESSFUL" in raw(path)
        assert "[LOGGINGAUDIT] FAILED" not in raw(path)
        assert "not committed" not in raw(path) and "Failed to drain database writes after server stop" not in raw(path)
        assert "NeoForge 21.1.248" in raw(path) and "GriefLogger 1.2.10-1.21.1-yuuniverse.6" in raw(path)
    normal = dict(state=state,logs=[evidence(p) for p in normal_logs],databases=base.database_evidence(directory,state,True))
    builds = ["logging-shutdown-before","logging-shutdown-final","logging-shutdown-item-final",*['logging-shutdown-'+mode+'-regression' for mode in ["full","reader","writer","normal"]]]
    binaries = [evidence(f"artifacts/{build}-build/classes/java/dialogue/net/yuuniverse/interactions/LoggingShutdownRuntimeAudit.class") for build in builds]
    assert len({item["sha256"] for item in binaries}) == 1
    assert not git("diff","796502e","--","neoforge/src/main","neoforge/src/dialogue","neoforge/src/test","neoforge/build.gradle","tools/audit-tests","tools/audit-tests.gradle")
    for package in previous["packages"]:
        assert evidence(package["path"]) == {key:package[key] for key in ["path","bytes","sha256"]}
        if "21.1.248-build" in package["path"] and not package["path"].endswith("-sources.jar"):
            source_set = "dialogue" if Path(package["path"]).name.startswith("yuuniverse-interactions") else "main"
            with zipfile.ZipFile(ROOT / package["path"]) as archive:
                for name in [n for n in archive.namelist() if n.endswith(".class")]:
                    for build in builds:
                        assert archive.read(name) == (ROOT / f"artifacts/{build}-build/classes/java/{source_set}" / name).read_bytes()
    ordinary = read("docs/craft-index-validation-2026-10-23.json")
    for test in [*ordinary["ordinary"],ordinary["interactions"]]:
        assert test["results"] == dict(tests=100 if test is ordinary["interactions"] else 828,failures=0,errors=0,skipped=0)
        for item in [test["log"],*test["reports"]]:
            assert evidence(item["path"]) == item
    for item in read(previous["unchangedPinnedClient"]["path"])["files"]:
        assert evidence(item["path"])["sha256"].upper() == item["sha256"]
    for build in builds[2:]:
        assert evidence(f"artifacts/{build}-server/mods/"+Path(provider6).name)["sha256"] == evidence(provider6)["sha256"]
    stopped = read("artifacts/logging-shutdown-stopped.json")
    assert len(stopped["fixtures"]) == 8 and len(stopped["snapshots"]) == 2
    for record in stopped["fixtures"] + stopped["snapshots"]:
        assert record["exclusive"]
        for item in record["files"]:
            assert evidence(item["path"]) == item
    guards = read("artifacts/logging-shutdown-launch-rejections.json")
    assert guards["before"] == guards["after"] and len(guards["checks"]) == 4
    for path, digest in guards["before"].items():
        assert evidence(path)["sha256"].upper() == digest
    for check in guards["checks"]:
        assert check["exitCode"] != 0 and check["expected"] in raw(check["log"]) and "Starting minecraft server" not in raw(check["log"])
    sources = ["tools/logging-runtime-audit.gradle","tools/grieflogger-compat/run-storage-tests.ps1"]
    sources += [item["path"] for item in files("tools/logging-runtime-audit") + files("tools/grieflogger-compat/storage-tests") if item["path"].endswith(".java")]
    for path in sources:
        assert (ROOT / path).read_bytes() == (ROOT / "artifacts/logging-shutdown-references" / Path(path).relative_to("tools")).read_bytes()
    provider_sources = []
    for source, revision in [(source5,"1ae929e"),(source6,"76670ee")]:
        provider_sources.extend(evidence(source / path) for path in git("diff","--name-only",revision,"HEAD",cwd=source).splitlines())
    result = dict(createdAt=datetime.now().astimezone().isoformat(),timezone="UTC+08:00",baseRevision="796502e",
        provider=evidence(provider6),providerCommit=git("rev-parse","HEAD",cwd=source6),initialProvider=evidence(provider5),initialCommit=git("rev-parse","HEAD",cwd=source5),
        providerSources=provider_sources,patches=[evidence(p) for p in patches],unchangedNestedDriverFiles=len(driver_files[2]),
        providerBuilds=[evidence(path) for path in build_logs],
        standalone=standalone,standaloneDatabases=standalone_dbs,failures=failures,shutdown=shutdown,regressions=regressions,normal=normal,nativeChecks=168,
        auditBinaries=binaries,packages=previous["packages"],reusedOrdinaryTestsPerTarget=828,reusedInteractionsTests=100,
        reusedEvidence=evidence("docs/craft-index-validation-2026-10-23.json"),unchangedPinnedClient=previous["unchangedPinnedClient"],
        stopped=stopped,stoppedEvidence=evidence("artifacts/logging-shutdown-stopped.json"),stoppedVerifier=evidence("artifacts/confirm-logging-shutdown-stopped.ps1"),
        guards=guards,guardEvidence=evidence("artifacts/logging-shutdown-launch-rejections.json"),guardVerifier=evidence("artifacts/check-logging-shutdown-guards.ps1"),
        sources=[evidence(p) for p in sources + ["tools/record-logging-shutdown-validation.py","tools/record-logging-full-validation.py","tools/record-guard-restart-validation.py"]],
        runner=evidence("artifacts/run-logging-shutdown-audit.ps1"),
        limits=["Native dedicated-server normal shutdown and process restart; physical clients/integrated lifecycle not accepted",
                "Player buffer drains on native quit, then SQL queues drain after shutdown producers finish",
                "Database stays at mod lifetime; same-process SQL/queue reuse is separately tested",
                "Failed final drain remains memory-only and reports failure; no crash spool or automatic uncertain replay",
                "Process kill/device loss, arbitrary external producers after stop, retention/load/MySQL and integrated release remain",
                "Original migration inputs, supported Paradigm inbound evaluation and deferred ordinary/VR/proxy tests remain",
                "No production or deferred client activation"])
    (ROOT / "docs/logging-shutdown-validation-2026-10-23.json").write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    print("PASS 109 actual SQLite + 168 native/restart checks, two reproduced loss paths, stopped NBT and original records")


if __name__ == "__main__":
    validate()
