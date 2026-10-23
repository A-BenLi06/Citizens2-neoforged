"""Verify the repaired provider, real SQLite fault tests and native logging restart."""
from pathlib import Path
from datetime import datetime
import hashlib
import io
import json
import re
import sqlite3
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8-sig"))


def evidence(path):
    path = (ROOT / path).resolve()
    data = path.read_bytes()
    name = path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else path.as_posix()
    return dict(path=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest())


def files(path):
    return [evidence(p) for p in sorted((ROOT / path).rglob("*")) if p.is_file()]


def git(*arguments, directory=ROOT):
    return subprocess.check_output(["git", *arguments], cwd=directory, text=True).strip()


def validate():
    source = ROOT / "artifacts/grieflogger-write-recovery-source"
    fixture = Path("artifacts/grieflogger-write-recovery-server")
    provider = source / "neoforge/build/libs/grieflogger-1.2.10-1.21.1-yuuniverse.2-neoforge.jar"
    original = Path("artifacts/logging-audit-server/mods/grieflogger-1.2.10-1.21.1-yuuniverse.1-neoforge.jar")
    assert not git("status", "--porcelain", directory=source)
    assert git("log", "-1", "--format=%an <%ae> / %cn <%ce>", directory=source) == "A-BenLi06 <ben_li06@outlook.com> / A-BenLi06 <ben_li06@outlook.com>"
    patch = "tools/grieflogger-compat/queued-write-recovery.patch"
    subprocess.run(["git", "apply", "--check", str(ROOT / patch)], cwd=ROOT / "artifacts/ecosystem-2026-10-13/GriefLogger", check=True)
    assert (ROOT / fixture / "mods" / provider.name).read_bytes() == provider.read_bytes()
    with zipfile.ZipFile(provider) as archive:
        assert not any(name.startswith("org/sqlite/") for name in archive.namelist())
        metadata = json.loads(archive.read("META-INF/jarjar/metadata.json"))
        drivers = [d for d in metadata["jars"] if d["identifier"] == dict(group="org.xerial", artifact="sqlite-jdbc")]
        assert len(drivers) == 1
        driver_bytes = archive.read(drivers[0]["path"])
        assert driver_bytes == (ROOT / "artifacts/grieflogger-storage-recovery-tests-final/sqlite.jar").read_bytes()
        with zipfile.ZipFile(ROOT / original) as old:
            old_metadata = json.loads(old.read("META-INF/jarjar/metadata.json"))
            old_driver = next(d for d in old_metadata["jars"] if d["identifier"] == drivers[0]["identifier"])
            old_driver_bytes = old.read(old_driver["path"])
            with zipfile.ZipFile(io.BytesIO(old_driver_bytes)) as before, zipfile.ZipFile(io.BytesIO(driver_bytes)) as after:
                before_contents = {n: before.read(n) for n in before.namelist() if not n.endswith("/")}
                after_contents = {n: after.read(n) for n in after.namelist() if not n.endswith("/")}
                assert before_contents == after_contents
        for name in ["QueuedStatement", "QueuedWrite", "Queue", "IQueue"]:
            assert f"com/daqem/grieflogger/database/queue/{name}.class" in archive.namelist()
    build_log = "artifacts/grieflogger-write-recovery-build.log"
    assert "BUILD SUCCESSFUL" in (ROOT / build_log).read_text(encoding="utf-8-sig")
    standalone_path = "artifacts/grieflogger-storage-recovery-tests-final/validation.json"
    standalone = read(standalone_path)
    assert standalone["checks"] == 27 and standalone["actualProviderBytecode"] and standalone["minecraftBootstrapOwnerSubstitutedForStandaloneLogger"]
    for item in standalone["files"]:
        actual = evidence(item["path"])
        assert actual["bytes"] == item["bytes"] and actual["sha256"] == item["sha256"]
    logs = ["artifacts/grieflogger-native-recovery.log", "artifacts/grieflogger-native-recovery-restart.log"]
    for path, marker in zip(logs, ["COMPLETE 37 checks", "COMPLETE 8 restart checks"]):
        raw = (ROOT / path).read_text(encoding="utf-8-sig")
        assert "BUILD SUCCESSFUL" in raw and f"[LOGGINGAUDIT] {marker}" in raw and "[LOGGINGAUDIT] FAILED" not in raw
        assert "NeoForge 21.1.248" in raw and "GriefLogger 1.2.10-1.21.1-yuuniverse.2" in raw
        assert "queued writes retained" not in raw and "Failed to commit database initialization" not in raw
    stopped_path = "artifacts/grieflogger-storage-stopped.json"
    stopped = read(stopped_path)
    assert stopped["exclusiveStoppedWorldAndDatabases"] and Path(stopped["fixture"]).resolve() == (ROOT / fixture).resolve()
    state = read(fixture / "logging-restart-state.json")
    databases = []
    for relative in ["database.db", "logging-world/yuuniverse_economy/economy.db"]:
        path = ROOT / fixture / relative
        assert not any(Path(str(path) + suffix).exists() for suffix in ["-wal", "-shm", "-journal"])
        with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as database:
            assert database.execute("PRAGMA quick_check").fetchall() == [("ok",)]
            if relative == "database.db":
                users = database.execute("SELECT id FROM users WHERE uuid=?", (state["actor"],)).fetchall()
                assert len(users) == 1
                rows = {table: database.execute(f'SELECT COUNT(*) FROM "{table}" WHERE user=?', (users[0][0],)).fetchone()[0] for table in ["blocks", "containers", "items"]}
                assert rows["blocks"] >= 192 and rows["containers"] > 0
            else:
                rows = database.execute("SELECT transaction_id,shop_id,currency_id,player_account_id FROM commerce_operation WHERE operation_id=?", (state["operation"],)).fetchall()
                assert len(rows) == 1 and rows[0][:3] == (state["transaction"], state["shop"], state["currency"])
                assert database.execute("SELECT COUNT(*) FROM ledger_transaction WHERE transaction_id=?", (state["transaction"],)).fetchone()[0] == 1
                assert database.execute("SELECT balance_minor_units FROM balance_projection WHERE account_id=? AND currency_id=?", (rows[0][3], state["currency"])).fetchall() == [(state["balance"],)]
                assert database.execute("SELECT transaction_id,currency_id,SUM(delta_minor_units) FROM ledger_entry GROUP BY transaction_id,currency_id HAVING SUM(delta_minor_units)<>0").fetchall() == []
            databases.append(dict(**evidence(path), quickCheck="ok", originalActorRows=rows))
    previous = read("docs/craft-binding-validation-2026-10-22.json")
    assert previous["productionSources"] == files("neoforge/src/main")
    assert subprocess.run(["git", "diff", "--quiet", "98b5525", "--", "neoforge/src/main", "neoforge/src/dialogue", "neoforge/src/test", "neoforge/build.gradle", "tools/audit-tests", "tools/audit-tests.gradle"], cwd=ROOT).returncode == 0
    packages = []
    for package in previous["packages"]:
        assert evidence(package["path"]) == {k: package[k] for k in ["path", "bytes", "sha256"]}
        if "21.1.248-build" in package["path"] and not package["path"].endswith("-sources.jar"):
            source_set = "dialogue" if Path(package["path"]).name.startswith("yuuniverse-interactions") else "main"
            with zipfile.ZipFile(ROOT / package["path"]) as archive:
                names = [n for n in archive.namelist() if n.endswith(".class")]
                for name in names:
                    assert archive.read(name) == (ROOT / f"artifacts/grieflogger-native-recovery-build/classes/java/{source_set}" / name).read_bytes(), name
            packages.append(dict(**evidence(package["path"]), matchingNativeProductionClasses=len(names)))
    for ordinary in previous["ordinary"]:
        assert ordinary["results"] == dict(tests=825, failures=0, errors=0, skipped=0)
        assert evidence(ordinary["log"]["path"]) == ordinary["log"]
        for report in ordinary["reports"]:
            assert evidence(report["path"]) == report
    assert previous["interactions"]["results"] == dict(tests=100, failures=0, errors=0, skipped=0)
    assert evidence(previous["interactions"]["log"]["path"]) == previous["interactions"]["log"]
    repro = Path("artifacts/grieflogger-storage-diagnostic-2026-10-23")
    assert "REPRODUCED: queued observation discarded after transient SQLite write lock" in (ROOT / repro / "diagnostic.log").read_text(encoding="utf-8-sig")
    changed = git("diff", "--name-only", "0017e15321badb496e4939c58ec7a60cf3821139", "HEAD", directory=source).splitlines()
    result = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", baseRevision="98b5525",
        providerCommit=git("rev-parse", "HEAD", directory=source), providerBase="0017e15321badb496e4939c58ec7a60cf3821139",
        provider=evidence(provider), originalProvider=evidence(original), providerSources=[evidence(source / p) for p in changed], patch=evidence(patch),
        sqliteDriverSha256=hashlib.sha256(driver_bytes).hexdigest(), originalSqliteDriverSha256=hashlib.sha256(old_driver_bytes).hexdigest(),
        unchangedDriverContents={n: hashlib.sha256(b).hexdigest() for n, b in sorted(after_contents.items())},
        baselineReproducer=files(repro), standalone=standalone, standaloneEvidence=evidence(standalone_path),
        nativeChecks=37, cleanRestartChecks=8, build=evidence(build_log), logs=[evidence(p) for p in logs], providers=files(fixture / "mods"),
        nativeState=state, nativeStateEvidence=evidence(fixture / "logging-restart-state.json"), databases=databases,
        stopped=stopped, stoppedEvidence=evidence(stopped_path), h2=evidence(fixture / "logging-world/neoprotect/neoprotect.mv.db"),
        packages=packages, reusedOrdinaryTestsPerTarget=825, reusedInteractionsTests=100, reusedTestEvidence="docs/craft-binding-validation-2026-10-22.json",
        sources=files("tools/grieflogger-compat/storage-tests") + [evidence(p) for p in ["tools/grieflogger-compat/run-storage-tests.ps1", "tools/logging-runtime-audit.gradle", "tools/logging-runtime-audit/net/yuuniverse/interactions/LoggingRuntimeAudit.java", "tools/record-grieflogger-storage-validation.py"]],
        limits=["Fault injection is actual provider bytecode and SQLite outside Minecraft with only bootstrap/logger substituted", "Native regression uses ordinary/restart workflows, not in-server injected database faults", "Pending writes remain in memory; unflushed JVM termination is not covered", "Uncertain commit/rollback requires review, not blind replay", "Capacity/retention/full-device/MySQL/load and migration/client gates remain", "No production or pinned ordinary-client fixture changes"])
    (ROOT / "docs/grieflogger-write-recovery-validation-2026-10-23.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("PASS GriefLogger recovery: 27 SQLite / 37 native / 8 restart checks, exact original financial state and unchanged Citizens packages")


if __name__ == "__main__":
    validate()
