"""Corroborate native party craft recovery with immutable journals, stopped saves and package provenance."""
from collections import Counter
from datetime import datetime
from pathlib import Path
import gzip
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import uuid
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("guard_nbt_reader", ROOT / "tools/record-guard-restart-validation.py")
nbt_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nbt_module)


def read(path):
    return json.loads((ROOT / path).read_text(encoding="utf-8-sig"))


def evidence(path):
    path = (ROOT / path).resolve()
    data = path.read_bytes()
    return dict(path=path.relative_to(ROOT).as_posix(), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())


def files(path):
    return [evidence(p) for p in sorted((ROOT / path).rglob("*")) if p.is_file()]


def native_inventory(world, actor, expected):
    path = ROOT / world / "playerdata" / (actor + ".dat")
    native = nbt_module.nbt(gzip.decompress(path.read_bytes()))
    assert str(uuid.UUID(int=sum((value & 0xffffffff) << (96 - 32 * index) for index, value in enumerate(native["UUID"])))) == actor
    result = [{} for _ in expected]
    seen = set()
    for stack in native["Inventory"]:
        stack = dict(stack)
        slot = stack.pop("Slot")
        slot = slot if 0 <= slot < 36 else slot - 100 + 36 if 100 <= slot <= 103 else 40 if slot == -106 else -1
        assert 0 <= slot < len(result) and slot not in seen
        seen.add(slot)
        result[slot] = stack
    assert result == expected
    return dict(file=evidence(path), inventory=result)


def field_values(path, name):
    text = (ROOT / path).read_text(encoding="utf-8")
    match = re.search(r"\b" + re.escape(name) + r":\s*\{([^}]*)\}", text)
    assert match is not None, (path, name)
    return dict(re.findall(r'([0-9A-Fa-f-]+):\s*"?([0-9A-Za-z_]+)"?L?', match[1]))


def validate():
    previous = read("docs/craft-index-validation-2026-10-23.json")
    subprocess.run(["git", "diff", "--exit-code", "f1ef1ad", "--", "neoforge/src/main", "neoforge/src/dialogue",
                    "neoforge/src/test", "neoforge/build.gradle", "tools/audit-tests", "tools/audit-tests.gradle"], cwd=ROOT, check=True)
    for item in previous["sources"]:
        assert evidence(item["path"]) == item
    for tests in [*previous["ordinary"], previous["interactions"]]:
        assert tests["results"]["failures"] == tests["results"]["errors"] == tests["results"]["skipped"] == 0
        for item in [tests["log"], *tests["reports"]]:
            assert evidence(item["path"]) == item
    missing_owner = "artifacts/craft-party-248-server"
    failure_log = (ROOT / "artifacts/craft-party-native-248-recover.log").read_text(encoding="utf-8-sig")
    assert "BUILD FAILED" in failure_log and "Native binding definition changed" in failure_log
    seed_image = "artifacts/craft-party-248-stopped-seed-world/citizens/quest-evidence"
    def immutable_images(directory):
        return {p.relative_to(ROOT / directory).as_posix(): evidence(p)["sha256"] for p in (ROOT / directory).rglob("*.json")}
    assert immutable_images(seed_image) == immutable_images(missing_owner + "/craft-world/citizens/quest-evidence")
    cases = []
    for target in ["248", "233"]:
        fixture = f"artifacts/craft-party-final-{target}-server"
        seed, recovered, final = [read(fixture + "/craft-party-" + name + ".json") for name in ["state", "recovered", "final"]]
        assert recovered["seedEntries"] == final["seedEntries"] == seed["seedEntries"]
        assert final["recoveredEntries"] == recovered["recoveredEntries"]
        assert [len(seed["seedEntries"]), len(recovered["recoveredEntries"]), len(final["finalEntries"])] == [2, 6, 7]
        assert final["finalCycle"] == 2 and final["finalProgress"] == 0 and final["personalProgress"] == 4
        assert len({e["admission"]["observationId"] for e in final["finalEntries"]}) == 7
        assert Counter(e["admission"]["actorId"] for e in final["finalEntries"]) == {seed["owner"]: 2, seed["member"]: 5}
        latest = {e["id"]: e for e in final["finalEntries"]}
        for entry in [*seed["seedEntries"], *recovered["recoveredEntries"]]:
            actual = latest[entry["id"]]
            assert actual["admission"] == entry["admission"] and actual["outcome"] == entry["outcome"] and actual["createdAt"] == entry["createdAt"]
        journal = ROOT / fixture / "craft-world/citizens/quest-evidence/journal"
        generations = {read(p)["payload"]["id"]: read(p)["payload"] for p in journal.glob("generation-*.json")}
        assert len(generations) == 3
        totals = Counter()
        for entry in final["finalEntries"]:
            assert entry["state"] == "APPLIED" and entry["outcome"] == "RETURNED" and entry["revision"] == 2
            assert read(journal / ("entry-" + entry["id"] + "-2.json"))["payload"] == entry
            admission = entry["admission"]
            assert admission["units"] == 4 and admission["taskId"] == seed["task"]
            payload = json.loads(admission["evidence"])
            assert payload["output"] == {"id": "minecraft:oak_planks", "count": 4}
            assert payload["inputs"][0] == {"id": "minecraft:oak_log", "count": 1} and all(v is None for v in payload["inputs"][1:])
            generation = generations[admission["generationId"]]
            assert generation["scope"]["questId"] == seed["quest"]
            totals[(generation["scope"]["teamId"], generation["nativeCycle"])] += admission["units"]
        assert totals == {(seed["party"], 0): 12, (seed["party"], 1): 12, (seed["personal"], 0): 4}
        snapshots = []
        for phase, inventories, cycle, progress, members in [
                ("seed", seed["seedInventories"], 0, 0, {seed["owner"]}),
                ("recover", recovered["recoveredInventories"], 1, 8, {seed["owner"], seed["member"]}),
                ("verify", final["finalInventories"], 2, 0, {seed["owner"], seed["member"]})]:
            world = f"artifacts/craft-party-final-{target}-stopped-{phase}-world"
            team = world + "/ftbquests/" + seed["party"] + ".snbt"
            assert int(field_values(team, "task_progress").get(seed["task"], "0").removesuffix("L")) == progress
            assert int(field_values(team, "completion_count").get(seed["quest"], "0").removesuffix("L")) == cycle
            personal_file = world + "/ftbquests/" + seed["personal"] + ".snbt"
            personal_progress = int(field_values(personal_file, "task_progress").get(seed["task"], "0").removesuffix("L"))
            assert personal_progress == (0 if phase == "seed" else 4)
            party_file = world + "/ftbteams/party/" + seed["party"] + ".snbt"
            assert set(field_values(party_file, "ranks")) == members
            snapshots.append(dict(phase=phase, cycle=cycle, progress=progress, personalProgress=personal_progress, members=sorted(members),
                                  players={actor: native_inventory(world, actor, json.loads(image)) for actor, image in inventories.items()},
                                  nativeFiles=files(world + "/ftbquests") + files(world + "/ftbteams") + files(world + "/citizens/quest-evidence")))
        logs = []
        for phase, count in [("seed", 10), ("recover", 23), ("verify", 12)]:
            path = f"artifacts/craft-party-final-{target}-{phase}.log"
            text = (ROOT / path).read_text(encoding="utf-8-sig")
            assert "BUILD SUCCESSFUL" in text and "[CRAFTRECOVERY] FAILED" not in text
            assert f"[CRAFTRECOVERY] COMPLETE {count} phase=party-{phase}" in text
            assert text.count("[CRAFTPARTY] PASS ") == count
            logs.append(dict(phase=phase, checks=count, log=evidence(path)))
        cases.append(dict(target="21.1." + target, seed=seed, recovered=recovered, final=final,
                          definition=evidence(fixture + "/craft-party-definition.json"),
                          logs=logs, snapshots=snapshots, providers=files(fixture + "/mods"),
                          preparation=evidence(f"artifacts/craft-party-final-{target}-prepare.log")))
    regression = []
    for target in ["248", "233"]:
        logs = []
        for phase, count in [("seed", 7), ("recover", 11)]:
            path = f"artifacts/craft-party-regression-{target}-{phase}.log"
            text = (ROOT / path).read_text(encoding="utf-8-sig")
            assert "BUILD SUCCESSFUL" in text and "[CRAFTRECOVERY] FAILED" not in text
            assert f"[CRAFTRECOVERY] COMPLETE {count} phase={phase}" in text
            logs.append(evidence(path))
        regression.append(dict(target="21.1." + target, checks=18, logs=logs,
                               observation=read(f"artifacts/craft-party-regression-{target}-server/craft-recovery-observation.json")))
    for package in previous["packages"]:
        assert evidence(package["path"]) == {k: package[k] for k in ["path", "bytes", "sha256"]}
        if package["path"].endswith("-sources.jar"):
            continue
        target = "248" if "21.1.248-build" in package["path"] else "233"
        source_set = "dialogue" if Path(package["path"]).name.startswith("yuuniverse") else "main"
        with zipfile.ZipFile(ROOT / package["path"]) as archive:
            names = [name for name in archive.namelist() if name.endswith(".class")]
            assert len(names) == package["matchingProductionClasses"]
            for name in names:
                assert archive.read(name) == (ROOT / f"artifacts/craft-party-final-{target}-build/classes/java/{source_set}" / name).read_bytes()
                assert archive.read(name) == (ROOT / f"artifacts/craft-party-regression-{target}-build/classes/java/{source_set}" / name).read_bytes()
    frozen = "artifacts/craft-party-final-frozen-sources"
    sources = files("tools/craft-recovery-runtime-audit") + [evidence("tools/" + name) for name in ["craft-recovery-runtime-audit.gradle", "prepare-craft-party-audit.ps1"]]
    for source in sources:
        if source["path"].endswith(".java") or not source["path"].endswith(".md"):
            assert evidence(Path(frozen) / Path(source["path"]).relative_to("tools"))["sha256"] == source["sha256"]
    binaries = []
    binary_sets = []
    for build in [f"craft-party-{kind}-{target}" for target in ["248", "233"] for kind in ["final", "regression"]]:
        directory = ROOT / f"artifacts/{build}-build/classes/java/main/net/citizensnpcs/audit"
        values = {p.name: p.read_bytes() for p in directory.glob("Craft*.class")}
        assert values
        binary_sets.append(values)
        binaries.extend(evidence(directory / name) for name in sorted(values))
    assert all(values == binary_sets[0] for values in binary_sets)
    guards = read("artifacts/craft-party-launch-rejections.json")
    assert guards["before"] == guards["after"] and len(guards["checks"]) == 4
    for path, digest in guards["before"].items():
        assert evidence(path)["sha256"].upper() == digest
    for check in guards["checks"]:
        text = (ROOT / check["log"]).read_text(encoding="utf-8-sig")
        assert check["exitCode"] != 0 and check["expected"] in text and "Starting minecraft server" not in text
    assert len(read("artifacts/craft-party-prepare-rejections.json")) == 2
    stopped = read("artifacts/craft-party-stopped.json")
    assert len(stopped["worlds"]) == 12 and len(stopped["journals"]) == 44
    client = previous["pinnedClientReference"]
    assert evidence(client["path"]) == client
    for item in read(client["path"])["files"]:
        assert evidence(item["path"])["sha256"].upper() == item["sha256"]
    result = dict(createdAt=datetime.now().astimezone().isoformat(), timezone="UTC+08:00", baseRevision="f1ef1ad", cases=cases,
                  nativeChecks=90, regression=regression, totalNativeChecks=126, auditBinaries=binaries,
                  sources=sources + [evidence("tools/record-craft-party-validation.py"), evidence("tools/record-guard-restart-validation.py")],
                  packages=previous["packages"], reusedOrdinaryTestsPerTarget=828, reusedInteractionsTests=100,
                  reusedEvidence=evidence("docs/craft-index-validation-2026-10-23.json"),
                  stopped=stopped, stoppedEvidence=evidence("artifacts/craft-party-stopped.json"),
                  stoppedVerifier=evidence("artifacts/confirm-craft-party-stopped.ps1"),
                  guardEvidence=evidence("artifacts/craft-party-launch-rejections.json"),
                  guardVerifier=evidence("artifacts/check-craft-party-guards.ps1"),
                  guardLogs=[evidence(c["log"]) for c in guards["checks"]],
                  prepareRejections=read("artifacts/craft-party-prepare-rejections.json"),
                  unchangedPinnedClient=client,
                  missingTaskOwnerFence=dict(log=evidence("artifacts/craft-party-native-248-recover.log"),
                                             unchangedImmutableRecords=len(immutable_images(seed_image)),
                                             sources=files("artifacts/craft-party-frozen-sources"),
                                             bytecode=[evidence("artifacts/" + name) for name in ["craft-party-customtask-bytecode.txt", "craft-party-customtask-event-bytecode.txt", "craft-party-basequest-bytecode.txt"]]),
                  limits=["Native synthetic sessions/result clicks and clean saved-world restarts, not physical clients or forced kills",
                          "CustomTask maximum is configured by its public initialization owner event, not persisted by FTB",
                          "Native CustomReward exercises repeat reset, not Economy monetary payment",
                          "Broader machine/party/backup/retention, migration inputs, inbound Paradigm and deferred clients remain open"])
    (ROOT / "docs/ftb-craft-party-validation-2026-10-23.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("PASS 90 party + 36 original recovery checks, stopped inventories/team progress, original evidence and unchanged ordinary packages")


if __name__ == "__main__":
    validate()
