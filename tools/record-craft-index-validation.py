"""Verify retained craft history, fresh tests, native recovery and ordinary package provenance."""
from datetime import datetime
from collections import Counter
from pathlib import Path
import hashlib
import json
import re
import subprocess
import tomllib
import xml.etree.ElementTree as ET
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


def successful(path, marker=None):
    raw = (ROOT / path).read_text(encoding="utf-8-sig")
    assert "BUILD SUCCESSFUL" in raw and "BUILD FAILED" not in raw, path
    if marker:
        assert marker in raw, (path, marker)
    return evidence(path)


def tests(build, log, expected):
    reports = sorted((ROOT / build / "test-results/test").glob("TEST-*.xml"))
    assert reports
    counts = {key: sum(int(ET.parse(p).getroot().get(key, "0")) for p in reports)
              for key in ["tests", "failures", "errors", "skipped"]}
    assert counts == dict(tests=expected, failures=0, errors=0, skipped=0), counts
    return dict(results=counts, log=successful(log), reports=[evidence(p) for p in reports])


def validate():
    result = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", baseRevision="6dc7eb6")
    load = "artifacts/craft-storage-2026-10-23"
    before = read("artifacts/craft-storage-before-images.json")
    assert len(before) == 15456
    actual = {str(p.relative_to(ROOT / load)).replace("\\", "/"): evidence(p)["sha256"]
              for p in (ROOT / load).rglob("*.json") if p.name not in ["scan-result.json", "indexed-result.json"]}
    assert {k.replace("\\", "/"): v for k, v in before.items()} == actual
    scan, indexed = read(load + "/scan-result.json"), read(load + "/indexed-result.json")
    assert [r["entries"] for r in scan["samples"]] == [256, 1024, 4096]
    assert [r["entries"] for r in indexed["samples"]] == [256, 1024, 4096]
    original = ROOT / "artifacts/craft-storage-scan-build/QuestEvidenceJournal.java"
    baseline = subprocess.check_output(["git", "show", "6dc7eb6:neoforge/src/main/java/net/citizensnpcs/util/QuestEvidenceJournal.java"], cwd=ROOT)
    assert baseline.replace(b"\r\n", b"\n") == original.read_bytes().replace(b"\r\n", b"\n")
    for name in ["QuestEvidenceJournal.java", "CraftQuestEvidence.java", "QuestEvidenceJournalTest.java"]:
        tree = "test" if name.endswith("Test.java") else "main"
        assert evidence(f"neoforge/src/{tree}/java/net/citizensnpcs/util/{name}")["sha256"] == evidence("artifacts/craft-index-source-snapshot/" + name)["sha256"]
    assert evidence("neoforge/src/main/java/net/citizensnpcs/util/QuestEvidenceJournal.java")["sha256"] == evidence("artifacts/craft-storage-indexed-build/QuestEvidenceJournal.java")["sha256"]
    result["storage"] = dict(scan=scan, indexed=indexed, unchangedImmutableFiles=len(before),
                             images=evidence("artifacts/craft-storage-before-images.json"),
                             evidence=[evidence(load + "/" + mode + "-result.json") for mode in ["scan", "indexed"]],
                             builds=files("artifacts/craft-storage-scan-build") + files("artifacts/craft-storage-indexed-build"),
                             logs=[evidence("artifacts/craft-storage-" + mode + ".log") for mode in ["scan", "indexed"]])
    ordinary, native, packages = [], [], []
    for target in ["248", "233"]:
        build = f"artifacts/craft-index-ordinary-21.1.{target}-build"
        ordinary.append(dict(target="21.1." + target, **tests(build, f"artifacts/craft-index-ordinary-{target}.log", 828)))
        fixture = f"artifacts/craft-index-{target}-server"
        state, observation = read(fixture + "/craft-recovery-state.json"), read(fixture + "/craft-recovery-observation.json")
        histories = files(fixture + "/craft-world/citizens/quest-evidence")
        assert len(state["entries"]) == 3
        for entry in state["entries"]:
            current = read(fixture + "/craft-world/citizens/quest-evidence/journal/entry-" + entry["id"] + "-2.json")["payload"]
            assert current["state"] == "APPLIED" and current["admission"] == entry["admission"]
        assert observation["progress"] == [0, 0, 1]
        assert all(e["state"] == "APPLIED" for e in observation["finalEntries"])
        team_file = ROOT / fixture / "craft-world/ftbquests" / (state["team"] + ".snbt")
        progress = re.search(r"task_progress:\s*\{([^}]*)\}", team_file.read_text(encoding="utf-8"))[1]
        native_progress = dict(re.findall(r"([0-9A-F]{16}):\s*(\d+)L", progress))
        assert native_progress == {task: "1" for task in state["tasks"]}
        native.append(dict(target="21.1." + target, state=state, observation=observation, histories=histories,
                           nativeProgress=native_progress,
                           nativeSaves=files(fixture + "/craft-world/ftbquests") + files(fixture + "/craft-world/ftbteams")
                           + files(fixture + "/craft-world/playerdata"), providers=files(fixture + "/mods"),
                           logs=[successful(f"artifacts/craft-index-native-{target}-{phase}.log", f"[CRAFTRECOVERY] COMPLETE {count} phase={phase}")
                                 for phase, count in [("seed", 7), ("recover", 11)]]))
        for jar in sorted((ROOT / build / "libs").glob("*.jar")):
            with zipfile.ZipFile(jar) as archive:
                names = archive.namelist()
                assert not any("/audit/" in n or n.startswith(("dev/ftb/", "com/simibubi/", "net/neoforged/")) for n in names)
                classes = [n for n in names if n.endswith(".class")]
                dialogue = jar.name.startswith("yuuniverse-interactions")
                if classes:
                    source_set = "dialogue" if dialogue else "main"
                    for name in classes:
                        assert archive.read(name) == (ROOT / build / "classes/java" / source_set / name).read_bytes()
                        assert archive.read(name) == (ROOT / f"artifacts/craft-index-native-{target}-build/classes/java" / source_set / name).read_bytes()
                        if target == "248":
                            assert archive.read(name) == (ROOT / "artifacts/craft-index-create-build/classes/java" / source_set / name).read_bytes()
                    owners = [mod["modId"] for mod in tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode())["mods"]]
                    assert owners == (["interactions"] if dialogue else ["citizens"]), owners
                else:
                    for source in (ROOT / "neoforge/src/main/java").rglob("*.java"):
                        assert archive.read(source.relative_to(ROOT / "neoforge/src/main/java").as_posix()) == source.read_bytes()
            packages.append(dict(**evidence(jar), matchingProductionClasses=len(classes)))
    assert len(packages) == 6
    result["ordinary"], result["native"], result["packages"] = ordinary, native, packages
    result["interactions"] = tests("artifacts/craft-index-interactions-build", "artifacts/craft-index-interactions.log", 100)
    create_log = (ROOT / "artifacts/craft-index-create.log").read_text(encoding="utf-8-sig")
    create_counts = Counter(re.findall(r"\[([A-Z]+AUDIT)\] PASS ", create_log))
    completions = {name: int(count) for name, count in re.findall(r"\[([A-Z]+AUDIT)\] COMPLETE (\d+) checks", create_log)}
    assert dict(create_counts) == completions and len(completions) == 9 and sum(completions.values()) == 263
    assert not re.search(r"\[[A-Z]+AUDIT\] FAILED", create_log)
    result["create"] = dict(checks=263, components=completions, log=successful("artifacts/craft-index-create.log", "[CREATEAUDIT] COMPLETE 54 checks"),
                            providers=files("artifacts/craft-index-create-final-server/mods"),
                            preparation=evidence("artifacts/craft-index-create-prepare.log"),
                            initialScript=evidence("artifacts/craft-index-create-initial-script.js"),
                            failedSetup=evidence("artifacts/craft-index-create-first.log"))
    result["stopped"] = read("artifacts/craft-index-stopped.json")
    assert len(result["stopped"]["worlds"]) == 5 and len(result["stopped"]["journals"]) == 28
    result["stoppedEvidence"] = evidence("artifacts/craft-index-stopped.json")
    result["stoppedVerifier"] = evidence("artifacts/confirm-craft-index-stopped.ps1")
    prior = read("docs/guard-restart-validation-2026-10-23.json")
    client = prior["unchangedPinnedClient"]
    assert evidence(client["path"]) == client
    pinned = read(client["path"])
    for item in pinned["files"]:
        assert evidence(item["path"])["sha256"].upper() == item["sha256"]
    result["pinnedClientReference"] = client
    result["sources"] = files("tools/craft-storage-audit") + [evidence(p) for p in [
        "tools/run-craft-storage-audit.ps1", "tools/record-craft-index-validation.py",
        "neoforge/src/main/java/net/citizensnpcs/util/QuestEvidenceJournal.java",
        "neoforge/src/main/java/net/citizensnpcs/util/CraftQuestEvidence.java",
        "neoforge/src/test/java/net/citizensnpcs/util/QuestEvidenceJournalTest.java"]]
    result["nativeHarnessSources"] = files("tools/craft-recovery-runtime-audit") + files("tools/create-runtime-audit")
    subprocess.run(["git", "diff", "--exit-code", "6dc7eb6", "--", "tools/craft-recovery-runtime-audit",
                    "tools/create-runtime-audit", "tools/craft-recovery-runtime-audit.gradle", "tools/create-runtime-audit.gradle"], cwd=ROOT, check=True)
    result["limits"] = ["Bounded warm journal lookup/allocation measurement, not production tick or startup throughput guarantees",
                         "Derived indexes add memory proportional to retained entries/scopes; total memory/disk remain unbounded",
                         "No pruning, schema change, separate persisted index or new crash/device-loss guarantee",
                         "Broader retention/recovery, original migration inputs, supported Paradigm inbound contract and deferred physical clients remain open"]
    (ROOT / "docs/craft-index-validation-2026-10-23.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("PASS unchanged 15,456 immutable records, 828 tests per target, 100 Interactions and 299 native checks")


if __name__ == "__main__":
    validate()
