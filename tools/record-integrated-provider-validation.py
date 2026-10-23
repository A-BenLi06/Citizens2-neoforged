"""Independently corroborate packaged coexistence, saved SQL and restart history."""
from collections import Counter
from datetime import datetime
from pathlib import Path
import argparse
import ctypes
import gzip
import hashlib
import importlib.util
import json
import re
import shutil
import socket
import sqlite3
import subprocess
import zipfile
import zlib

ROOT = Path(__file__).resolve().parent.parent
PHASES = ["xmod", "quest", "logging", "logging-recover"]


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def evidence(path):
    path = Path(path).resolve()
    return dict(path=path.relative_to(ROOT).as_posix(), bytes=path.stat().st_size,
                sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def checked(record):
    path = ROOT / record["path"]
    actual = evidence(path)
    assert actual["bytes"] == record["bytes"] and actual["sha256"] == record["sha256"].lower(), path
    return path


def exclusive(path):
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateFileW.restype = ctypes.c_void_p
    kernel.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p]
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    handle = kernel.CreateFileW(str(path), 0x80000000, 0, None, 3, 0x80, None)
    assert handle != ctypes.c_void_p(-1).value, (path, ctypes.get_last_error())
    kernel.CloseHandle(handle)


def sql_snapshot(path):
    exclusive(path)
    assert not any(Path(str(path)+suffix).exists() for suffix in ["-wal", "-shm", "-journal"])
    tables = {}
    with sqlite3.connect(path.as_uri()+"?mode=ro&immutable=1", uri=True) as db:
        assert db.execute("PRAGMA quick_check").fetchall() == [("ok",)]
        for name, definition in db.execute("SELECT name,sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"):
            assert re.fullmatch(r"[A-Za-z0-9_]+", name)
            rows = [[dict(blob=v.hex()) if isinstance(v, bytes) else v for v in row] for row in db.execute('SELECT * FROM "'+name+'"')]
            tables[name] = dict(sql=definition, rows=sorted(rows, key=lambda row: json.dumps(row, ensure_ascii=False, sort_keys=True)))
        if "ledger_entry" in tables:
            assert not db.execute("SELECT transaction_id,currency_id,SUM(delta_minor_units) FROM ledger_entry GROUP BY transaction_id,currency_id HAVING SUM(delta_minor_units)<>0").fetchall()
            balances = {(account, currency): amount for account, currency, amount in db.execute("SELECT account_id,currency_id,SUM(delta_minor_units) FROM ledger_entry GROUP BY account_id,currency_id")}
            projected = {(row[0], row[1]): row[2] for row in db.execute("SELECT account_id,currency_id,balance_minor_units FROM balance_projection")}
            assert all(amount == balances.get(key, 0) for key, amount in projected.items())
            assert set(balances).issubset(projected)
        if "blocks" in tables:
            assert not db.execute("PRAGMA foreign_key_check(blocks)").fetchall()
            assert [r[1] for r in db.execute("PRAGMA table_xinfo(blocks)")] == ["time", "user", "level", "x", "y", "z", "type", "action", "material_type", "entity_type"]
            for row in tables["blocks"]["rows"]:
                assert row[8:] == ([None, row[6]] if row[7] == 3 else [row[6], None])
    return tables


def runtime_inventory(directory, candidate):
    values = read(directory / "loaded-providers.json")
    actual = {v["id"]: v["version"] for v in values}
    assert len(actual) == len(values)
    for record in candidate["mods"]:
        assert set(record["modIds"]).issubset(actual), record
    required = dict(minecraft="1.21.1", neoforge="21.1.248", citizens="2.0.43-neoforge-SNAPSHOT",
                    interactions="2.0.43-neoforge-SNAPSHOT", yuuniverse_economy="0.5.9-SNAPSHOT",
                    grieflogger="1.2.10-1.21.1-yuuniverse.10", ftbquests="2101.1.36", ftbteams="2101.1.11",
                    ftblibrary="2101.1.37", ftbxmodcompat="21.1.12", rsitemeditor="1.0.1", paradigm="2.5.0",
                    neoprotect="1.0.4", kubejs="2101.7.2-build.377", vivecraft="1.21.1-1.3.15",
                    guardvillagers="2.4.12", create="6.0.10-mc1.21.1-performance-v6", axiom="6.1.3")
    assert all(actual.get(key) == value for key, value in required.items()), actual
    return actual


def package_provenance(fixture):
    marker = read(fixture / "integrated-audit-fixture.json")
    candidate = read(fixture / "integrated-audit-candidate.json")
    assert len(candidate["mods"]) == len(marker["mods"]) == 64
    assert len(candidate["auditClasses"]) == 29
    for record in candidate["mods"]:
        checked(record)
    for record in candidate["auditClasses"]:
        checked(record)
    by_owner = {owner: ROOT / r["path"] for r in candidate["mods"] for owner in r["modIds"]}
    additions = {}
    for owner in ["citizens", "interactions"]:
        original = checked(marker["releaseInputs"][owner])
        with zipfile.ZipFile(original) as release, zipfile.ZipFile(by_owner[owner]) as probe:
            assert len(probe.namelist()) == len(set(probe.namelist()))
            assert set(release.namelist()).issubset(probe.namelist())
            assert all(release.read(name) == probe.read(name) for name in release.namelist())
            added = sorted(set(probe.namelist()) - set(release.namelist()))
            assert all(name.endswith(".class") for name in added)
            assert all(any(r["path"].endswith("/"+name) and r["sha256"] == hashlib.sha256(probe.read(name)).hexdigest() for r in candidate["auditClasses"]) for name in added)
            additions[owner] = added
    for owner, record in marker["replacements"].items():
        checked(record)
        if owner not in additions:
            assert evidence(by_owner[owner])["sha256"] == record["sha256"]
    return dict(marker=evidence(fixture / "integrated-audit-fixture.json"), candidate=evidence(fixture / "integrated-audit-candidate.json"), additions=additions)


def rows(values):
    return Counter(json.dumps(row, ensure_ascii=False, sort_keys=True) for row in values)


def launch_guards(fixture):
    protected = sorted(path for folder in [fixture / "runs", fixture / "integrated-world", fixture / "config", fixture / "mods"] for path in folder.rglob("*") if path.is_file())
    protected += [fixture / "database.db", fixture / "server.properties"]
    before = [evidence(path) for path in protected]
    attempts = []
    for phase in PHASES:
        process = subprocess.run(["python", "-B", str(ROOT / "tools/integrated-runtime-audit.py"), "run", "--fixture", str(fixture),
                                  "--phase", phase, "--java-home", str(ROOT / "artifacts/nonexistent-integrated-guard-java")], cwd=ROOT, capture_output=True)
        text = (process.stdout + process.stderr).decode("utf-8", errors="replace")
        expected = "Do not replay inherited completed acceptance" if phase == "xmod" else "Preserve existing integrated phase attempt"
        assert process.returncode != 0 and expected in text, text
        attempts.append(dict(phase=phase, exitCode=process.returncode, expected=expected, output=text))
    assert before == [evidence(path) for path in protected]
    output = ROOT / "artifacts/integrated-provider-launch-guards.json"
    output.write_text(json.dumps(dict(verifiedAt=datetime.now().astimezone().isoformat(), protectedFiles=len(before), attempts=attempts), indent=2)+"\n", encoding="utf-8")
    return evidence(output)


def validate(fixture, output):
    seed = read(fixture / "integrated-audit-seed.json") if (fixture / "integrated-audit-seed.json").exists() else None
    origins = {phase: ROOT / seed["parentFixture"] if seed and PHASES.index(phase) <= PHASES.index(seed["afterPhase"]) else fixture for phase in PHASES}
    packages = {str(origin.relative_to(ROOT)): package_provenance(origin) for origin in set(origins.values())}
    base_revision = read(origins["xmod"] / "integrated-audit-fixture.json")["baseRevision"]
    subprocess.run(["git", "diff", "--exit-code", base_revision, "--", "neoforge/src/main", "neoforge/src/dialogue", "neoforge/src/test", "neoforge/build.gradle"], cwd=ROOT, check=True)
    if seed:
        checked(seed["result"])
        inherited_result = read(ROOT / seed["result"]["path"])
        accepted = {}
        inherited_directory = (ROOT / seed["result"]["path"]).parent
        for record in inherited_result["nativeFiles"]:
            relative = (ROOT / record["path"]).relative_to(inherited_directory)
            if relative.parts[0] == "world":
                relative = Path("integrated-world") / Path(*relative.parts[1:])
            accepted[relative.as_posix()] = (record["bytes"], record["sha256"])
        logger_seed = read(inherited_directory / "grieflogger-snapshot.json")["database"]
        accepted["database.db"] = (logger_seed["bytes"], logger_seed["sha256"])
        actual_seed = {(ROOT / record["path"]).relative_to(fixture).as_posix(): (record["bytes"], record["sha256"]) for record in seed["liveFiles"]}
        assert accepted == actual_seed
    records, inventories, economy, logger = [], [], [], []
    for phase in PHASES:
        origin = origins[phase]
        directory = origin / "runs" / phase
        result = read(directory / "result.json")
        assert result["phase"] == phase and result["exitCode"] == 0 and result["stopped"]
        process = read(directory / "process.json")
        exit_path = directory / "exit.json"
        exit_state = read(exit_path if exit_path.exists() else ROOT / "artifacts/integrated-provider-candidate-xmod-process-exit.json")
        assert exit_state["pid"] == process["pid"] and exit_state["exitCode"] == 0
        checked(result["console"])
        checked(result["serverLog"])
        text = (directory / "console.log").read_text(encoding="utf-8", errors="replace")
        assert not re.search(r"\[(?:QUEST|XMOD|LOGGING|INTEGRATED)AUDIT\] FAILED", text)
        markers = re.findall(r"\[([A-Z]+AUDIT)\] COMPLETE (\d+) (?:restart )?checks", text)
        expected_tags = {"xmod": {"XMODAUDIT", "PREPAREDCOMMERCEAUDIT", "COMMERCETASKAUDIT", "PARTYREWARDAUDIT"}, "quest": {"QUESTAUDIT"}, "logging": {"LOGGINGAUDIT"}, "logging-recover": {"LOGGINGAUDIT"}}[phase]
        assert {tag for tag, _ in markers} == expected_tags
        for tag, count in markers:
            assert len(re.findall(r"\["+tag+r"\] PASS ", text)) == int(count), (phase, tag)
        inventories.append(runtime_inventory(directory, read(origin / "integrated-audit-candidate.json")))
        for item in result["nativeFiles"]:
            checked(item)
        world = directory / "world"
        exclusive(world / "session.lock")
        exclusive(world / "neoprotect/neoprotect.mv.db")
        saved = {}
        for name, item in result["snapshots"].items():
            snapshot = read(checked(item))
            relative = Path(snapshot["database"]["path"]).relative_to(origin.relative_to(ROOT))
            copy = world / relative.relative_to("integrated-world") if relative.parts[0] == "integrated-world" else directory / relative
            assert copy.is_file(), copy
            assert evidence(copy)["sha256"] == snapshot["database"]["sha256"]
            assert sql_snapshot(copy) == snapshot["tables"]
            saved[name] = snapshot["tables"]
        economy.append(saved["economy"])
        logger.append(saved["grieflogger"])
        records.append(dict(phase=phase, origin=origin.relative_to(ROOT).as_posix(), result=evidence(directory / "result.json"),
                            process=evidence(directory / "process.json"), exit=evidence(exit_path if exit_path.exists() else ROOT / "artifacts/integrated-provider-candidate-xmod-process-exit.json"),
                            checks=sum(int(count) for _, count in markers), loadedProviders=evidence(directory / "loaded-providers.json")))
    assert all(value == inventories[0] for value in inventories)
    immutable = ["ledger_transaction", "ledger_entry", "commerce_operation", "commerce_journal"]
    for before, after in zip(economy, economy[1:]):
        for table in immutable:
            assert before[table]["sql"] == after[table]["sql"]
            assert not (rows(before[table]["rows"]) - rows(after[table]["rows"])), table
    for before, after in zip(logger, logger[1:]):
        for table in before:
            assert before[table]["sql"] == after[table]["sql"]
            assert not (rows(before[table]["rows"]) - rows(after[table]["rows"])), table
    # These phases add no new activity for the original commerce/reward actors.
    for table in immutable:
        assert economy[0][table] == economy[1][table]
        assert economy[2][table] == economy[3][table]
    for later in economy[1:]:
        assert economy[0]["currency"]["sql"] == later["currency"]["sql"]
        assert [row[:-1] for row in economy[0]["currency"]["rows"]] == [row[:-1] for row in later["currency"]["rows"]]
        original_players = [row for row in economy[0]["balance_projection"]["rows"] if row[0].startswith("player:")]
        assert not (rows(original_players) - rows(later["balance_projection"]["rows"]))
    for before, after in zip(economy, economy[1:]):
        assert all(new[-1] >= old[-1] for old, new in zip(before["currency"]["rows"], after["currency"]["rows"], strict=True))
    preserved_journals = []
    first_world = origins["xmod"] / "runs/xmod/world"
    for path in sorted((first_world / "citizens").rglob("*.json")):
        relative = path.relative_to(first_world)
        for phase in PHASES[1:]:
            actual = origins[phase] / "runs" / phase / "world" / relative
            assert actual.is_file() and path.read_bytes() == actual.read_bytes(), (phase, relative)
        preserved_journals.append(evidence(path))
    native_originals = []
    for pattern in ["ftbquests/*.snbt", "playerdata/*.dat"]:
        originals = sorted(first_world.glob(pattern))
        assert originals, pattern
        for path in originals:
            relative = path.relative_to(first_world)
            for phase in PHASES[1:]:
                actual = origins[phase] / "runs" / phase / "world" / relative
                assert actual.is_file() and actual.read_bytes() == path.read_bytes(), (phase, relative)
            native_originals.append(evidence(path))
    state = read(fixture / "logging-restart-state.json")
    spec = importlib.util.spec_from_file_location("integrated_native_nbt", ROOT / "tools/record-guard-restart-validation.py")
    native = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(native)
    inventory_path = fixture / "runs/logging-recover/world/playerdata" / (state["actor"]+".dat")
    inventory = native.nbt(gzip.decompress(inventory_path.read_bytes()))["Inventory"]
    assert sum(item["count"] for item in inventory if item["id"] == "minecraft:stone") == 2
    region_path = fixture / "runs/logging-recover/world/region/r.0.0.mca"
    region = region_path.read_bytes()
    location = int.from_bytes(region[:4], "big")
    offset, sectors = location >> 8, location & 255
    assert offset >= 2 and sectors > 0
    start = offset * 4096
    size = int.from_bytes(region[start:start+4], "big")
    assert 1 < size <= sectors * 4096 - 4 and start + size + 4 <= len(region)
    payload = region[start+5:start+4+size]
    compression = region[start+4]
    assert compression in [1, 2, 3]
    chunk = native.nbt(gzip.decompress(payload) if compression == 1 else zlib.decompress(payload) if compression == 2 else payload)
    restored = [block for block in chunk["block_entities"] if block["id"] == "minecraft:chest" and any(item["id"] == "minecraft:paper" for item in block.get("Items", []))]
    assert len(restored) == 1
    assert [restored[0][key] for key in ["x", "y", "z"]] == [7, -60, 3]
    named_paper = [item for item in restored[0]["Items"] if item["id"] == "minecraft:paper"]
    assert sum(item["count"] for item in named_paper) == 7
    assert all("Logging components" in str(item["components"]["minecraft:custom_name"]) for item in named_paper)
    with sqlite3.connect((fixture / "integrated-world/yuuniverse_economy/economy.db").as_uri()+"?mode=ro&immutable=1", uri=True) as db:
        receipt = db.execute("SELECT transaction_id,shop_id,currency_id,player_account_id FROM commerce_operation WHERE operation_id=?", (state["operation"],)).fetchall()
        assert len(receipt) == 1 and receipt[0][:3] == (state["transaction"], state["shop"], state["currency"])
        assert db.execute("SELECT balance_minor_units FROM balance_projection WHERE account_id=? AND currency_id=?", (receipt[0][3], state["currency"])).fetchall() == [(state["balance"],)]
        claims = {row[0] for row in db.execute("SELECT claim_id FROM delivery_claim WHERE transaction_id=?", (state["transaction"],))}
        assert len(claims) == 1
        assert all(item["components"]["minecraft:custom_data"]["yuuniverse_economy_delivery_claim"] in claims for item in inventory if item["id"] == "minecraft:stone")
    final = read(fixture / "runs/logging-recover/result.json")
    for record in final["liveFiles"]:
        checked(record)
    exclusive(fixture / "integrated-world/session.lock")
    exclusive(fixture / "integrated-world/neoprotect/neoprotect.mv.db")
    with socket.socket() as probe:
        assert probe.connect_ex(("127.0.0.1", 25641)) != 0
    original = ROOT / "artifacts/ordinary-client-acceptance-server"
    exclusive(original / "acceptance-world/session.lock")
    exclusive(original / "acceptance-world/neoprotect/neoprotect.mv.db")
    original_manifest = read(original / "acceptance-manifest.json")
    for record in original_manifest["files"]:
        assert evidence(original / "mods" / record["name"])["sha256"] == record["sha256"].lower()
    # Compare original/deferred stopped data to the preceding accepted manifest.
    previous = read(ROOT / "docs/logging-entity-validation-2026-10-23.json")
    original_protected = []
    def retained(value):
        if isinstance(value, dict):
            if {"path", "bytes", "sha256"}.issubset(value) and ("ordinary-client-acceptance-server/" in value["path"] or "native-grieflogger" in value["path"]):
                checked(value)
                original_protected.append(value)
            for child in value.values():
                retained(child)
        elif isinstance(value, list):
            for child in value:
                retained(child)
    retained(previous)
    pinned_client = read(checked(previous["unchangedPinnedClient"]))
    for record in pinned_client["files"]:
        actual = evidence(Path(record["path"]))
        assert actual["sha256"] == record["sha256"].lower(), record["path"]
        original_protected.append(actual)
    references = []
    for path in [ROOT / "tools/integrated-runtime-audit.py", ROOT / "tools/integrated-runtime-audit.gradle", Path(__file__),
                 ROOT / "tools/prepare-quest-audit.ps1", ROOT / "tools/paradigm-file-audit/ParadigmHologramFileAudit.java", ROOT / "tools/record-guard-restart-validation.py"]:
        references.append(evidence(path))
    references.extend(evidence(path) for folder in ["tools/xmod-runtime-audit", "tools/logging-runtime-audit", "tools/quest-runtime-audit", "tools/integrated-runtime-audit", "artifacts/integrated-initial-executable-reference", "artifacts/integrated-second-executable-reference"] for path in sorted((ROOT / folder).rglob("*")) if path.is_file())
    hologram_log = ROOT / "artifacts/integrated-paradigm-file-audit.log"
    assert "REPRODUCED installed Paradigm" in hologram_log.read_text(encoding="utf-8-sig")
    hologram_reproduction = dict(log=evidence(hologram_log), bytecode=evidence(ROOT / "artifacts/integrated-paradigm-hologramstore-bytecode.txt"),
                                reviewedSource=evidence(ROOT / "artifacts/paradigm-formatting-source/Common/src/main/java/eu/avalanche7/paradigm/modules/holograms/HologramStore.java"),
                                classes=[evidence(path) for path in sorted((ROOT / "artifacts/integrated-paradigm-file-audit-classes").glob("*.class"))])
    failure_records = []
    for name, expected in [("candidate", "java.lang.IllegalArgumentException: Currency is unavailable"),
                           ("continuation", "net.yuuniverse.economy.api.ShopApiException: Unknown enabled system shop: absent")]:
        failed_fixture = ROOT / ("artifacts/integrated-provider-"+name+"-server")
        failure = (failed_fixture / "runs/quest/console.log").read_text(encoding="utf-8", errors="replace")
        assert "[QUESTAUDIT] FAILED" in failure and expected in failure
        assert read(failed_fixture / "runs/quest/exit.json")["exitCode"] == 0
        exclusive(failed_fixture / "integrated-world/session.lock")
        exclusive(failed_fixture / "integrated-world/neoprotect/neoprotect.mv.db")
        failure_records.extend(evidence(path) for folder in [failed_fixture / "integrated-world", failed_fixture / "config", failed_fixture / "runs/quest"] for path in sorted(folder.rglob("*")) if path.is_file())
        failure_records.append(evidence(failed_fixture / "database.db"))
    guards = launch_guards(fixture)
    preparations = [evidence(ROOT / path) for path in ["artifacts/integrated-audit-before-descriptor-inventory.py", "artifacts/integrated-audit-before-bootstrap-inventory.py", "artifacts/integrated-candidate-initial-prepare.log",
                    "artifacts/integrated-provider-candidate-compile.log", "artifacts/integrated-provider-continuation-compile.log", "artifacts/integrated-provider-final-compile.log",
                    "artifacts/integrated-provider-independent-before-currency-metadata.log", "artifacts/integrated-verifier-before-currency-metadata.py"]]
    frozen = []
    reference_directory = ROOT / "artifacts/integrated-provider-executable-reference"
    for record in references:
        source = checked(record)
        destination = reference_directory / record["path"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            assert source.read_bytes() == destination.read_bytes(), destination
        else:
            shutil.copyfile(source, destination)
        frozen.append(dict(source=record, reference=evidence(destination)))
    runtime_inputs = [evidence(path) for path in sorted((fixture / "kubejs/server_scripts").rglob("*")) if path.is_file()]
    runtime_inputs.extend(evidence(fixture / name) for name in ["server.properties", "launch-args.txt", "eula.txt"])
    report = dict(verifiedAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", fixture=fixture.relative_to(ROOT).as_posix(),
                  packages=packages, phases=records, totalChecks=sum(r["checks"] for r in records), loadedProviderCount=len(inventories[0]),
                  preservedImmutableTables=immutable, preservedOriginalJournalFiles=preserved_journals,
                  preservedNativeTeamAndPlayerFiles=native_originals,
                  loggingState=evidence(fixture / "logging-restart-state.json"), seed=evidence(fixture / "integrated-audit-seed.json") if seed else None,
                  nativeInventory=dict(file=evidence(inventory_path), inventory=inventory),
                  nativeRestoredContainer=dict(file=evidence(region_path), container=restored[0]),
                  sources=references, originalProtectedFiles=original_protected, launchGuards=guards, hologramReproduction=hologram_reproduction,
                  excludedFailedAttempt=failure_records,
                  baseRevision=base_revision, unchangedProductionSources=True, preparationReferences=preparations,
                  executableReferences=frozen, finalRuntimeInputs=runtime_inputs,
                  limits=["Synthetic packaged server acceptance; physical clients remain deferred", "Not a complete migrated-world, sustained-load or hard-kill recovery run", "Paradigm 2.5.0 holograms disable after Windows restart file replacement failure; recorded separately", "Audit-augmented archives are fixture-only, not release artifacts"])
    output.write_text(json.dumps(report, indent=2, ensure_ascii=False)+"\n", encoding="utf-8")
    print("PASS independent packaged provider verification:", report["totalChecks"], "checks,", len(inventories[0]), "resolved providers")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    validate((ROOT / args.fixture).resolve(), (ROOT / args.output).resolve())
